// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import XCTest
import MediaAccessibility

/// Runs only against the explicitly supplied private pilot account. Configure test-runner
/// PICTURESHOW_PILOT_JSON through a local .xctestrun, never a checked-in test plan.
final class AppleSliceUITests: XCTestCase {
    var app: XCUIApplication!
    var config: [String: String] = [:]
    var sampleFolderOpen = false
    override func setUpWithError() throws {
        continueAfterFailure = false
        if name.contains("testTVNativeSelectedTrackEntryThenArrowHasNoLatePullback") || name.contains("testF4PhonePreparedMenuInitialSourceBackBackgroundAndReplacement")
            || name.contains("testF4TVNativeEarlyMenuBackDoesNotReopenAfterWorkerCompletion")
            || name.contains("testF4TVNativeEarlyMenuMountsWhenWorkerCompletesWithoutReopening") {
            app = XCUIApplication()
            return // This named case owns a finite local HTTP fixture, with no live account.
        }
        let json = try XCTUnwrap(ProcessInfo.processInfo.environment["PICTURESHOW_PILOT_JSON"], "Supply a private pilot configuration to run the live simulator suite")
        config = try JSONDecoder().decode([String:String].self, from: Data(json.utf8))
        app = XCUIApplication()
        app.launchEnvironment["PICTURESHOW_TEST_SERVER"] = config["server"]
        app.launchEnvironment["PICTURESHOW_TEST_USERNAME"] = config["username"]
        app.launchEnvironment["PICTURESHOW_TEST_PASSWORD"] = config["password"]
        if config["detailDiagnostics"] == "1" { app.launchEnvironment["PICTURESHOW_DETAIL_DIAGNOSTICS"] = "1" }
        app.launch()
    }
    func testLiveVerticalSlice() throws {
        XCTAssertNotNil(config["directContainers"], "Private pilot configuration must include the sample folder hierarchy")
        print("Pilot folder hierarchy: \(config["directContainers"] ?? "missing") / \(config["hlsContainers"] ?? "missing")")
        if hasHomeRoot(timeout: 2) || hasAuthenticatedNavigationState() {
            returnHome()
            let signOut = app.buttons["signOut"]
            reveal(signOut)
            if signOut.waitForExistence(timeout: 5) { activate(signOut) }
            // A signed-out clone can remain at the remembered-server chooser. Start a new
            // password flow from there instead of assuming it has no saved account.
            let addServer = app.buttons["addServer"]
            if addServer.waitForExistence(timeout: 3) { activate(addServer) }
        }
        XCTAssertTrue(app.textFields["serverAddress"].waitForExistence(timeout: 10))
        XCTAssertEqual(app.textFields["serverAddress"].label, "Server address")
        let addressLabel = app.textFields["serverAddress"].label
        capture("Server address sign-in")
        activate(app.buttons["connect"])
        XCTAssertTrue(app.buttons["signIn"].waitForExistence(timeout: 15))
        let username = app.textFields["username"]
        reveal(username)
        let password = app.secureTextFields["password"]
        reveal(password)
        dismissKeyboardAndTypingIntro()
        // Capture the real password form before any label assertion can fail. The password
        // field remains secure and the one-time Quick Connect code never appears in this screen.
        capture("Password sign-in")
        XCTAssertEqual(username.label, "Username")
        XCTAssertEqual(password.label, "Password")
        print("F1_AX login_address_label=\(addressLabel) username_label=\(username.label) password_label=\(password.label)")
        let quickConnectAction = app.buttons["quickConnect"]
        if quickConnectAction.waitForExistence(timeout: 3) {
            reveal(quickConnectAction)
            XCTAssertFalse(app.staticTexts["quickCode"].exists, "The safe password-form capture must not show a Quick Connect code")
            capture("Password sign-in lower actions")
        }
        activate(app.buttons["signIn"])
        XCTAssertTrue(hasHomeRoot(timeout: 90), "Successful password sign-in should return to the authenticated Home navigation root")
        try playAndResume(id: XCTUnwrap(config["directID"]), expected: "Direct play", key: "direct")
        try playAndResume(id: XCTUnwrap(config["hlsID"]), expected: "Server HLS", key: "hls")
        if let resumeID = config["resumeID"] { try playAndResume(id: resumeID, expected: "Server HLS", key: "resume") }
    }
    /// Keep unsupported-codec playback and server resume observable even if the
    /// separate direct-decoder gate fails on the simulator's media stack.
    func testServerHLSAndResume() throws {
        ensureSignedIn()
        #if os(tvOS)
        // The short VP9 fixture is covered by the independent native clock/Menu case. Run the
        // resume-eligible long item alone here so a replay-from-zero case cannot mask its result.
        try playAndResume(id: XCTUnwrap(config["resumeID"]), expected: "Server HLS", key: "resume")
        #else
        try playAndResume(id: XCTUnwrap(config["hlsID"]), expected: "Server HLS", key: "hls")
        try playAndResume(id: XCTUnwrap(config["resumeID"]), expected: "Server HLS", key: "resume")
        #endif
    }
    /// Covers the non-player navigation surfaces added by the native follow-up without relying
    /// on screenshots as semantic evidence. The private config names a visible demo-library item.
    func testAccountSearchSettingsAboutAndLicenses() throws {
        ensureSignedIn()
        capture("Home")

        let accounts = app.buttons["openAccounts"]
        reveal(accounts); activate(accounts)
        XCTAssertTrue(app.staticTexts["accountsTitle"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "account-")).firstMatch.exists)
        capture("Accounts")
        returnHome()

        #if os(iOS)
        let libraries = app.buttons["Libraries"]
        XCTAssertTrue(libraries.waitForExistence(timeout: 10), "The iOS tab or iPad side rail should expose Libraries")
        activate(libraries)
        capture("Libraries root")
        let libraryID = try XCTUnwrap(config["libraryID"])
        let library = app.buttons["item-\(libraryID)"]
        let librarySort = app.descendants(matching: .any).matching(identifier: "librarySort").firstMatch
        let samplesFolderID = try XCTUnwrap(config["samplesFolderID"])
        let configuredSamplesFolder = app.buttons["item-\(samplesFolderID)"]
        if librarySort.waitForExistence(timeout: 2), configuredSamplesFolder.waitForExistence(timeout: 2) {
            // NavigationMemory keeps each tab's own stack; the Libraries tab may restore the
            // previously visited Movies root instead of its library shelf. The immediate
            // samples folder identifies that root; the configured direct item is nested inside it.
            capture("Restored Movies root")
        } else {
            if librarySort.exists { back() }
            reveal(library); activate(library)
            XCTAssertTrue(librarySort.waitForExistence(timeout: 15), "Opening the Movies library should expose its sort controls")
            XCTAssertTrue(configuredSamplesFolder.waitForExistence(timeout: 15), "The opened Movies root must contain the configured immediate samples folder")
            capture("Movies root")
        }
        returnHome()
        #endif

        let search = app.buttons["openSearch"]
        reveal(search); activate(search)
        let field = app.textFields["searchField"]
        XCTAssertTrue(field.waitForExistence(timeout: 10))
        capture("Search empty state")
        activate(field)
        field.typeText(config["searchQuery"] ?? "Sample H264 AAC mov_text")
        let searchItem = try XCTUnwrap(config["searchItemID"] ?? config["directID"])
        let result = app.buttons["item-\(searchItem)"]
        XCTAssertTrue(result.waitForExistence(timeout: 20))
        capture("Search results with keyboard")
        submitAndDismissSearchKeyboard(field: field, result: result)
        capture("Search results keyboard dismissed")
        returnHome()

        let settings = app.buttons["openSettings"]
        reveal(settings); activate(settings)
        XCTAssertTrue(app.descendants(matching: .any).matching(identifier: "settingsTitle").firstMatch.waitForExistence(timeout: 10))
        let maxBitrate = app.descendants(matching: .any).matching(identifier: "maxBitrate").firstMatch
        reveal(maxBitrate)
        XCTAssertTrue(maxBitrate.waitForExistence(timeout: 10))
        let audioLanguage = app.descendants(matching: .any).matching(identifier: "audioLanguage").firstMatch
        reveal(audioLanguage)
        XCTAssertTrue(audioLanguage.waitForExistence(timeout: 10))
        capture("Settings")

        let diagnostics = app.buttons["openDiagnostics"]
        reveal(diagnostics); activate(diagnostics)
        XCTAssertTrue(app.staticTexts["This device"].waitForExistence(timeout: 10))
        capture("This device diagnostics")
        back()

        let supporter = app.buttons["openSupporter"]
        reveal(supporter); activate(supporter)
        XCTAssertTrue(app.staticTexts["billingUnavailable"].waitForExistence(timeout: 10))
        capture("Support information")
        back()

        let about = app.buttons["openAbout"]
        reveal(about); activate(about)
        XCTAssertTrue(app.staticTexts["aboutTitle"].waitForExistence(timeout: 10))
        capture("About")
        let licenses = app.buttons["openLicenses"]
        reveal(licenses); activate(licenses)
        let mpl = app.buttons["license-MPL-2.0"]
        XCTAssertTrue(mpl.waitForExistence(timeout: 10))
        capture("Licenses")
        reveal(mpl); activate(mpl)
        XCTAssertTrue(app.staticTexts["licenseTitle"].waitForExistence(timeout: 10))
        capture("MPL license text")
    }
    #if os(tvOS)
    /// Freshly checks the TV Settings navigation bar and a stable search-results layout without
    /// repeating account or license flows already covered by the broader family test.
    func testTVSettingsTitleAndSearchResultsAfterKeyboardDismissal() throws {
        ensureSignedIn()
        let settings = app.buttons["openSettings"]
        reveal(settings); activate(settings)
        let settingsTitle = app.descendants(matching: .any).matching(identifier: "settingsTitle").firstMatch
        XCTAssertTrue(settingsTitle.waitForExistence(timeout: 10))
        let bitrate = app.descendants(matching: .any).matching(identifier: "maxBitrate").firstMatch
        reveal(bitrate)
        XCTAssertTrue(bitrate.waitForExistence(timeout: 10))
        let audioLanguage = app.descendants(matching: .any).matching(identifier: "audioLanguage").firstMatch
        reveal(audioLanguage)
        XCTAssertTrue(audioLanguage.waitForExistence(timeout: 10))
        capture("TV Settings title with Audio output visible")

        returnHome()
        let search = app.buttons["openSearch"]
        reveal(search); activate(search)
        let field = app.textFields["searchField"]
        XCTAssertTrue(field.waitForExistence(timeout: 10))
        activate(field)
        field.typeText(config["searchQuery"] ?? "Sample H264 AAC mov_text")
        let searchItem = try XCTUnwrap(config["searchItemID"] ?? config["directID"])
        let result = app.buttons["item-\(searchItem)"]
        XCTAssertTrue(result.waitForExistence(timeout: 20))
        capture("TV Search results with keyboard")
        submitAndDismissSearchKeyboard(field: field, result: result)
        capture("TV Search results keyboard dismissed")
    }
    #endif
    /// Confirms iPad layout survives rotation while the primary app sections remain reachable.
    func testiPadLandscapeAccountAndCatalogNavigation() throws {
        #if os(iOS)
        guard UIDevice.current.userInterfaceIdiom == .pad else { throw XCTSkip("This acceptance case runs on an iPad destination") }
        ensureSignedIn()
        selectHomeRoot()
        XCTAssertTrue(app.buttons["openSearch"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["openSettings"].waitForExistence(timeout: 10))
        capture("iPad portrait home")
        let portraitSearch = app.buttons["openSearch"]
        activate(portraitSearch)
        XCTAssertTrue(app.textFields["searchField"].waitForExistence(timeout: 10))
        capture("iPad portrait search")
        returnHome()

        XCUIDevice.shared.orientation = .landscapeLeft
        defer { XCUIDevice.shared.orientation = .portrait }
        let homeLandscapeDeadline = ProcessInfo.processInfo.systemUptime + 10
        while ProcessInfo.processInfo.systemUptime < homeLandscapeDeadline && app.frame.width <= app.frame.height {
            Thread.sleep(forTimeInterval: 0.2)
        }
        let homeLandscapeFrame = app.frame
        print("F1_METRIC ipad_landscape_home orientation=\(XCUIDevice.shared.orientation) frame=\(homeLandscapeFrame)")
        XCTAssertGreaterThan(homeLandscapeFrame.width, homeLandscapeFrame.height, "The actual app window must settle to landscape before Home capture")
        ensureSignedIn()
        XCTAssertTrue(app.buttons["openSearch"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["openSettings"].waitForExistence(timeout: 10))
        capture("iPad landscape home")
        let library = app.buttons["Libraries"]
        XCTAssertTrue(library.waitForExistence(timeout: 10), "iPad root should expose the Libraries section")
        activate(library)
        capture("iPad landscape Libraries root")
        let libraryID = try XCTUnwrap(config["libraryID"])
        let movieLibrary = app.buttons["item-\(libraryID)"]
        let samplesFolderID = try XCTUnwrap(config["samplesFolderID"])
        let knownFolder = app.buttons["item-\(samplesFolderID)"]
        let librarySort = app.descendants(matching: .any).matching(identifier: "librarySort").firstMatch
        if librarySort.waitForExistence(timeout: 2), knownFolder.exists {
            capture("iPad landscape restored Movies root")
        } else {
            if librarySort.exists { back() }
            reveal(movieLibrary); activate(movieLibrary)
            XCTAssertTrue(librarySort.waitForExistence(timeout: 15))
            XCTAssertTrue(knownFolder.waitForExistence(timeout: 15), "The Movies root must contain its configured immediate Format Lab folder")
            capture("iPad landscape Movies root")
        }
        returnHome()
        let search = app.buttons["openSearch"]
        activate(search)
        XCTAssertTrue(app.textFields["searchField"].waitForExistence(timeout: 10))
        capture("iPad landscape search")

        // Keep one HLS viewing alive through portrait and landscape, then use the normal
        // Pictureshow close action so this required case proves its own Stop/back path.
        XCUIDevice.shared.orientation = .portrait
        returnHome()
        try openSampleDetail(id: XCTUnwrap(config["hlsID"]), key: "hls")
        let play = app.buttons["play"]
        reveal(play); activate(play)
        let method = app.staticTexts["playbackMethod"]
        XCTAssertTrue(method.waitForExistence(timeout: 30))
        XCTAssertTrue(waitForSettledMethod(method, expected: "Server HLS", timeout: 45), "The iPad HLS player should settle before measuring orientation changes")
        let position = app.staticTexts["playbackPosition"]
        XCTAssertTrue(position.waitForExistence(timeout: 10))
        XCTAssertTrue(waitForPlaybackClock(position, minimum: seconds(position.label) + 3, timeout: 40, metric: "ipad_portrait"))
        XCTAssertGreaterThan(app.frame.height, app.frame.width, "The first iPad playback capture should be portrait")
        print("F1_METRIC ipad_portrait_player orientation=\(XCUIDevice.shared.orientation) frame=\(app.frame)")
        capture("iPad HLS player portrait")

        XCUIDevice.shared.orientation = .landscapeLeft
        let landscapeDeadline = ProcessInfo.processInfo.systemUptime + 10
        while ProcessInfo.processInfo.systemUptime < landscapeDeadline && app.frame.width <= app.frame.height {
            Thread.sleep(forTimeInterval: 0.2)
        }
        let landscapeFrame = app.frame
        print("F1_METRIC ipad_landscape_player orientation=\(XCUIDevice.shared.orientation) frame=\(landscapeFrame)")
        XCTAssertGreaterThan(landscapeFrame.width, landscapeFrame.height, "The actual app window must settle to landscape before capture")
        let landscapeStart = seconds(position.label)
        XCTAssertTrue(waitForPlaybackClock(position, minimum: landscapeStart + 3, timeout: 40, metric: "ipad_landscape_player"))
        capture("iPad HLS player landscape")
        print("F1_SIMCTL_CAPTURE_POINT ipad_landscape_player")
        Thread.sleep(forTimeInterval: 2)

        XCUIDevice.shared.orientation = .portrait
        let portraitReturnDeadline = ProcessInfo.processInfo.systemUptime + 10
        while ProcessInfo.processInfo.systemUptime < portraitReturnDeadline && app.frame.height <= app.frame.width {
            Thread.sleep(forTimeInterval: 0.2)
        }
        print("F1_METRIC ipad_portrait_return orientation=\(XCUIDevice.shared.orientation) frame=\(app.frame)")
        XCTAssertGreaterThan(app.frame.height, app.frame.width, "The player should return to a settled portrait window")
        let portraitReturnStart = seconds(position.label)
        XCTAssertTrue(waitForPlaybackClock(position, minimum: portraitReturnStart + 2, timeout: 35, metric: "ipad_portrait_return"))
        capture("iPad HLS player returned portrait")
        activate(app.buttons["closePlayer"])
        XCTAssertTrue(app.staticTexts["detailTitle"].waitForExistence(timeout: 15), "Normal Back should return from the rotated player to the same detail")
        capture("iPad HLS detail after normal Back")
        #endif
    }
    /// Produces uncropped device-screen and paired-simctl evidence for settled iPad landscape layouts.
    func testiPadLandscapeFullScreenNavigationEvidence() throws {
        #if os(iOS)
        guard UIDevice.current.userInterfaceIdiom == .pad else { throw XCTSkip("This acceptance case runs on an iPad destination") }
        ensureSignedIn()
        selectHomeRoot()
        XCUIDevice.shared.orientation = .landscapeLeft
        defer { XCUIDevice.shared.orientation = .portrait }

        let deadline = ProcessInfo.processInfo.systemUptime + 15
        while ProcessInfo.processInfo.systemUptime < deadline && app.frame.width <= app.frame.height {
            Thread.sleep(forTimeInterval: 0.2)
        }
        let homeFrame = app.frame
        print("F1_METRIC ipad_landscape_fullscreen_home orientation=\(XCUIDevice.shared.orientation) frame=\(homeFrame)")
        XCTAssertGreaterThan(homeFrame.width, homeFrame.height, "The app window must be landscape before full-screen capture")
        XCTAssertTrue(app.buttons["openSearch"].waitForExistence(timeout: 10))
        captureFullScreen("iPad landscape Home full device screen")
        print("F1_SIMCTL_CAPTURE_POINT ipad_landscape_home")
        Thread.sleep(forTimeInterval: 2)

        let libraries = app.buttons["Libraries"]
        XCTAssertTrue(libraries.waitForExistence(timeout: 10))
        activate(libraries)
        let moviesID = try XCTUnwrap(config["libraryID"])
        let movies = app.buttons["item-\(moviesID)"]
        let librarySort = app.descendants(matching: .any).matching(identifier: "librarySort").firstMatch
        for _ in 0..<12 where !movies.exists || librarySort.exists {
            back()
            Thread.sleep(forTimeInterval: 0.25)
        }
        XCTAssertTrue(movies.waitForExistence(timeout: 15), "The Libraries shelf should show the configured Movies library after popping its retained route")
        XCTAssertFalse(librarySort.exists, "The Libraries screenshot must show its root shelf, not a restored library grid")
        captureFullScreen("iPad landscape Libraries root full device screen")
        print("F1_SIMCTL_CAPTURE_POINT ipad_landscape_libraries")
        Thread.sleep(forTimeInterval: 2)

        reveal(movies); activate(movies)
        let sort = app.descendants(matching: .any).matching(identifier: "librarySort").firstMatch
        let folderID = try XCTUnwrap(config["samplesFolderID"])
        let folder = app.buttons["item-\(folderID)"]
        XCTAssertTrue(sort.waitForExistence(timeout: 15), "The configured Movies root should expose its catalog sort control")
        XCTAssertTrue(folder.waitForExistence(timeout: 15), "The configured Movies root should contain its Format Lab folder")
        captureFullScreen("iPad landscape Movies root full device screen")
        print("F1_SIMCTL_CAPTURE_POINT ipad_landscape_movies_root")
        Thread.sleep(forTimeInterval: 2)
        #endif
    }
    /// Optional AVKit fullscreen probe, isolated from required rotation and normal Back.
    func testiPadNativeFullscreenRoundTripIfAvailable() throws {
        #if os(iOS)
        guard UIDevice.current.userInterfaceIdiom == .pad else { throw XCTSkip("This optional probe runs on an iPad destination") }
        ensureSignedIn()
        selectHomeRoot()
        try openSampleDetail(id: XCTUnwrap(config["hlsID"]), key: "hls")
        let play = app.buttons["play"]
        reveal(play); activate(play)
        let method = app.staticTexts["playbackMethod"]
        XCTAssertTrue(waitForSettledMethod(method, expected: "Server HLS", timeout: 45))
        let position = app.staticTexts["playbackPosition"]
        XCTAssertTrue(position.waitForExistence(timeout: 10))
        XCTAssertTrue(waitForPlaybackClock(position, minimum: seconds(position.label) + 3, timeout: 40, metric: "ipad_fullscreen_setup"))
        let fullScreen = app.buttons.matching(NSPredicate(format: "label CONTAINS[c] %@ OR label CONTAINS[c] %@ OR label CONTAINS[c] %@", "full screen", "fullscreen", "expand")).firstMatch
        if !fullScreen.waitForExistence(timeout: 10) {
            let labels = app.buttons.allElementsBoundByIndex.map(\.label).filter { !$0.isEmpty }
            print("F1_AX iPad_native_player_buttons " + labels.joined(separator: " | "))
            capture("iPad fullscreen control unavailable")
            activate(app.buttons["closePlayer"])
            XCTAssertTrue(app.staticTexts["detailTitle"].waitForExistence(timeout: 15))
            throw XCTSkip("AVKit did not expose a fullscreen expansion control; required rotation and normal Back are tested separately")
        }
        activate(fullScreen)
        let exitFullScreen = app.buttons.matching(NSPredicate(format: "label CONTAINS[c] %@ OR label CONTAINS[c] %@ OR label CONTAINS[c] %@", "exit full screen", "shrink", "inline")).firstMatch
        let stateDeadline = ProcessInfo.processInfo.systemUptime + 10
        while ProcessInfo.processInfo.systemUptime < stateDeadline && !exitFullScreen.exists {
            Thread.sleep(forTimeInterval: 0.25)
        }
        guard exitFullScreen.exists else {
            capture("iPad fullscreen state unverified")
            activate(app.buttons["closePlayer"])
            XCTAssertTrue(app.staticTexts["detailTitle"].waitForExistence(timeout: 15))
            throw XCTSkip("AVKit fullscreen state did not change observably; required rotation and normal Back are tested separately")
        }
        capture("iPad native player expanded fullscreen")
        activate(exitFullScreen)
        XCTAssertTrue(fullScreen.waitForExistence(timeout: 10))
        XCTAssertTrue(waitForPlaybackClock(position, minimum: seconds(position.label) + 2, timeout: 35, metric: "ipad_fullscreen_return"))
        capture("iPad native player returned inline")
        activate(app.buttons["closePlayer"])
        XCTAssertTrue(app.staticTexts["detailTitle"].waitForExistence(timeout: 15))
        #endif
    }

    /// Isolates actual AVPlayer startup from catalog navigation when diagnosing a decoder
    /// or transport failure. The full slice test remains the navigation acceptance gate.
    func testDirectPlaybackStartup() throws {
        ensureSignedIn()
        app.terminate()
        app.launchEnvironment["PICTURESHOW_TEST_DETAIL_ID"] = try XCTUnwrap(config["directID"])
        app.launch()
        let playFromStart = try zeroPositionPlayAction()
        reveal(playFromStart)
        capture("Movie detail")
        let playRequestedAt = ProcessInfo.processInfo.systemUptime
        activate(playFromStart)
        #if os(tvOS)
        let initialInfo = try XCTUnwrap(openNativeTVInfoTab(), "The native Info surface must expose a method and clock together")
        print("F1_METRIC direct_method_presentation_ms=\(Int((ProcessInfo.processInfo.systemUptime - playRequestedAt) * 1000)) method=\(initialInfo.method)")
        capture("Direct startup initial preparation")
        let initial = try XCTUnwrap(waitForTVPlayerClock(identifier: "playbackPosition", minimum: 0, timeout: 30,
                                                        metric: "direct_initial", expectedMethod: "Direct play"))
        let target = max(3, initial.seconds + 3)
        print("F1_METRIC direct_initial_clock_seconds=\(initial.seconds) observed_advance_target_seconds=\(target)")
        let advanced = try XCTUnwrap(waitForTVPlayerClock(identifier: "playbackPosition", minimum: target, timeout: 40,
                                                         metric: "direct", expectedMethod: "Direct play"),
                                    "Direct playback must visibly advance by at least three actual seconds inside the native player")
        print("F1_METRIC direct_clock_advanced_from_play_ms=\(Int((ProcessInfo.processInfo.systemUptime - playRequestedAt) * 1000)) seconds=\(advanced.seconds) method=\(advanced.method ?? "<missing>")")
        XCTAssertEqual(advanced.method, "Direct play")
        capture("Direct startup advancing video")
        leaveNativeTVPlayer(returningTo: playFromStart)
        #else
        let method = app.staticTexts["playbackMethod"]
        XCTAssertTrue(method.waitForExistence(timeout: 30))
        print("F1_METRIC direct_method_presentation_ms=\(Int((ProcessInfo.processInfo.systemUptime - playRequestedAt) * 1000)) method=\(method.label)")
        capture("Direct startup initial preparation")
        let position = app.staticTexts["playbackPosition"]
        XCTAssertTrue(position.waitForExistence(timeout: 10), "The player should expose an accessible current position")
        assertTouchPlayerActionLabels()
        XCTAssertFalse(position.label.isEmpty, "The initial clock value must be present before direct playback is measured")
        let initialSeconds = seconds(position.label)
        let observedAdvanceTarget = max(3, initialSeconds + 3)
        print("F1_METRIC direct_initial_clock_seconds=\(initialSeconds) observed_advance_target_seconds=\(observedAdvanceTarget)")
        XCTAssertTrue(waitForPlaybackClock(position, minimum: observedAdvanceTarget, timeout: 40, metric: "direct"))
        print("F1_METRIC direct_clock_advanced_from_play_ms=\(Int((ProcessInfo.processInfo.systemUptime - playRequestedAt) * 1000)) seconds=\(seconds(position.label))")
        XCTAssertEqual(method.label, "Direct play")
        capture("Direct startup advancing video")
        activate(app.buttons["closePlayer"])
        #endif
        XCTAssertTrue(playFromStart.waitForExistence(timeout: 15))
    }
    /// External plain text is an optional overlay on the existing Direct viewing.
    /// Its exact cue window and same-time Off/On captures distinguish it from burn-in.
    func testExternalTextSubtitleOverlayPreservesDirectViewing() throws {
        ensureSignedIn()
        let index = try XCTUnwrap(config["externalSubtitleIndex"].flatMap(Int.init))
        let codec = try XCTUnwrap(config["externalSubtitleCodec"]).lowercased()
        let cue = try XCTUnwrap(config["externalSubtitleCueSeconds"].flatMap(Int.init))
        let cueEnd = try XCTUnwrap(config["externalSubtitleCueEndSeconds"].flatMap(Int.init))
        XCTAssertGreaterThanOrEqual(index, 0, "External subtitle index must identify a real track, including index zero")
        XCTAssertTrue(["srt", "subrip", "vtt", "webvtt"].contains(codec), "This case requires an actual external SRT/VTT fixture, never embedded mov_text")
        XCTAssertGreaterThan(cue, 2)
        XCTAssertGreaterThanOrEqual(cueEnd - cue, 3, "The configured cue must allow a bounded real-time pause and capture")
        app.terminate()
        app.launchEnvironment["PICTURESHOW_TEST_DETAIL_ID"] = try XCTUnwrap(config["directID"])
        app.launch()
        let play = try zeroPositionPlayAction()
        reveal(play); activate(play)
        let nativePlayer = app.descendants(matching: .any).matching(identifier: "pictureshowNativePlayer").firstMatch
        XCTAssertTrue(nativePlayer.waitForExistence(timeout: 15), "Native video must be identified on both platforms before any scoped interaction")
        let method = app.descendants(matching: .any).matching(identifier: "playbackMethod").firstMatch
        var position: XCUIElement
        #if os(tvOS)
        position = app.descendants(matching: .any).matching(identifier: "AXElapsedTime").firstMatch
        if !position.waitForExistence(timeout: 2) { XCUIRemote.shared.press(.up) }
        #else
        position = app.staticTexts["playbackPosition"]
        XCTAssertTrue(method.waitForExistence(timeout: 30))
        XCTAssertTrue(waitForSettledMethod(method, expected: "Direct play", timeout: 45))
        #endif
        XCTAssertTrue(position.waitForExistence(timeout: 30))
        let advancing = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            guard let value = self.parsedClockSeconds(position.label) else { return false }
            return value >= 2
        }, object: position)
        XCTAssertEqual(XCTWaiter.wait(for: [advancing], timeout: 40), .completed, "The zero-start Direct viewing must show at least two seconds of actual playback")

        XCTAssertTrue(nativePlayer.exists, "An ended/dismissed player cannot satisfy the paused-caption setup")
        if let duration = config["directRunSeconds"].flatMap(Int.init), duration > 0 {
            XCTAssertLessThan(seconds(position.label), duration, "A viewing that reached EOS before Pause is not a valid paused-caption setup")
        }
        #if os(iOS)
        func touchNativeTransport(_ label: String) throws {
            let opposite = label == "Pause" ? "Play" : "Pause"
            let control = nativePlayer.buttons.matching(NSPredicate(format: "label ==[c] %@", label)).firstMatch
            let achieved = nativePlayer.buttons.matching(NSPredicate(format: "label ==[c] %@", opposite)).firstMatch
            let videoFrame = nativePlayer.frame
            func usableFrame(_ button: XCUIElement) -> CGRect? {
                guard button.exists, button.isHittable else { return nil }
                let frame = button.frame
                guard frame.width > 0, frame.height > 0,
                      videoFrame.width > 0, videoFrame.height > 0,
                      videoFrame.contains(frame) else { return nil }
                return frame
            }
            func usable(_ button: XCUIElement) -> Bool { usableFrame(button) != nil }
            func failTransport(_ reason: String) throws -> Never {
                var controls: [String] = []
                if let snapshot = try? nativePlayer.snapshot() {
                    func collect(_ node: XCUIElementSnapshot) {
                        if node.elementType == .button && ["Pause", "Play"].contains(node.label) {
                            controls.append("\(node.label):\(node.frame)")
                        }
                        node.children.forEach(collect)
                    }
                    collect(snapshot)
                }
                print("F2_AX external_native_transport requested=\(label) nativeFrame=\(videoFrame) method=\(method.label) clock=\(position.label) controls=\(controls.joined(separator: " | "))")
                capture("External text native transport failure")
                XCTFail(reason)
                throw NSError(domain: "External native transport", code: 1)
            }
            // The opposite action positively identifies the state we want. A reveal
            // can expose it without requiring another toggle of the native player.
            if usable(achieved) { return }
            if !usable(control) {
                // Revealing at the center can itself hit AVKit's Play/Pause button.
                nativePlayer.coordinate(withNormalizedOffset: CGVector(dx: 0.25, dy: 0.35)).tap()
                let deadline = ProcessInfo.processInfo.systemUptime + 5
                while ProcessInfo.processInfo.systemUptime < deadline && !usable(achieved) && !usable(control) {
                    Thread.sleep(forTimeInterval: 0.2)
                }
            }
            if usable(achieved) { return }
            guard let controlFrame = usableFrame(control) else {
                try failTransport("The real AVKit video must expose a hittable, contained \(label) or \(opposite) control")
            }
            // The generic activation wait can outlast AVKit's visible HUD. Dispatch
            // through the stable player at the native button frame just verified.
            let target = CGVector(dx: (controlFrame.midX - videoFrame.minX) / videoFrame.width,
                                  dy: (controlFrame.midY - videoFrame.minY) / videoFrame.height)
            print("F2_GESTURE external_native_transport requested=\(label) buttonFrame=\(controlFrame) nativeFrame=\(videoFrame)")
            nativePlayer.coordinate(withNormalizedOffset: target).tap()
            guard achieved.waitForExistence(timeout: 5), usable(achieved) else {
                try failTransport("The native \(label) action must expose its contained, hittable \(opposite) state")
            }
        }
        try touchNativeTransport("Pause")
        func captureTouchCueState(_ reason: String) {
            if let snapshot = try? app.snapshot() {
                var nodes: [XCUIElementSnapshot] = []
                func collect(_ node: XCUIElementSnapshot) {
                    nodes.append(node)
                    node.children.forEach(collect)
                }
                collect(snapshot)
                let video = nodes.first { $0.identifier == "pictureshowNativePlayer" }
                let observedMethod = nodes.first { $0.identifier == "playbackMethod" }?.label ?? "<missing>"
                let observedClock = nodes.first { $0.identifier == "playbackPosition" }?.label ?? "<missing>"
                nodes.removeAll()
                if let video { collect(video) }
                let controls = nodes.filter { $0.elementType == .button && ["Play", "Pause"].contains($0.label) }
                    .map { "\($0.label):\($0.frame)" }.joined(separator: " | ")
                let observedFrame = video.map { String(describing: $0.frame) } ?? "<missing>"
                print("F2_AX external_cue_state reason=\(reason) nativeFrame=\(observedFrame) method=\(observedMethod) clock=\(observedClock) controls=\(controls)")
            }
            capture("External text cue state before failed assertion")
        }
        #else
        XCUIRemote.shared.press(.playPause)
        #endif
        var paused = seconds(position.label)
        #if os(tvOS)
        openNativeTVInfoTab()
        position = app.descendants(matching: .any).matching(identifier: "playbackPosition").firstMatch
        #endif
        XCTAssertTrue(method.waitForExistence(timeout: 15))
        XCTAssertEqual(method.label, "Direct play", "The original native viewing must be Direct before optional text changes")
        XCTAssertTrue(waitForPausedClockNear(position, target: paused, tolerance: 1, timeout: 10, metric: "external_initial_pause"))
        Thread.sleep(forTimeInterval: 1)
        XCTAssertLessThanOrEqual(abs(seconds(position.label) - paused), 1)

        // Menu navigation can consume wall time before Pause. Rewind using the
        // existing app-owned controls, then prove the real paused clock clamps
        // to zero before choosing optional text. No synthetic timeline is used.
        #if os(iOS)
        activate(app.buttons["playerSeek"])
        XCTAssertTrue(app.sliders["Seek position"].waitForExistence(timeout: 10))
        #endif
        let back10 = app.descendants(matching: .any).matching(identifier: "seekBackward").firstMatch
        XCTAssertTrue(back10.waitForExistence(timeout: 10))
        var rewindPresses = 0
        while seconds(position.label) > 0 && rewindPresses < 7 {
            let before = seconds(position.label)
            let target = max(0, before - 10)
            #if os(tvOS)
            activateNativeTVInfoAction(identifier: "seekBackward")
            #else
            reveal(back10); activate(back10)
            #endif
            XCTAssertTrue(waitForPausedClockNear(position, target: target, tolerance: target == 0 ? 0 : 1, timeout: 15,
                                                 metric: "external_back10_\(rewindPresses + 1)"),
                          "Each real Back 10 action must move the observed paused player clock toward zero")
            XCTAssertLessThan(seconds(position.label), before, "A seek label alone cannot prove the actual playback position changed")
            rewindPresses += 1
        }
        #if os(iOS)
        activate(app.buttons["playerOptionsDone"])
        #endif
        XCTAssertTrue(waitForPausedClockNear(position, target: 0, tolerance: 0, timeout: 15, metric: "external_zero_clamp"))
        paused = seconds(position.label)
        XCTAssertEqual(paused, 0, "The bounded real rewind must observe the zero clamp before Off/external selection")
        Thread.sleep(forTimeInterval: 2)
        XCTAssertEqual(seconds(position.label), 0, "Rewinding must retain paused intent rather than resume the A/V item")
        XCTAssertEqual(method.label, "Direct play")
        print("F2_METRIC external_text_rewind presses=\(rewindPresses) actual_paused_clock=\(seconds(position.label)) method=\(method.label)")
        capture("External text 00 real paused Back 10 clamp at zero")

        func chooseExternalSubtitle(_ selectedIndex: String, tag: String) {
            #if os(tvOS)
            _ = selectNativeTVMenuTrack(menuTitle: "Subtitles", index: selectedIndex)
            XCTAssertTrue(inspectNativeTVMenuSelection(menuTitle: "Subtitles", index: selectedIndex), "The native menu must check the exact external/Off selection")
            capture("External text \(tag) native selected checkmark")
            XCUIRemote.shared.press(.menu)
            XCTAssertTrue(nativePlayer.waitForExistence(timeout: 5))
            openNativeTVInfoTab()
            #else
            selectTouchTrack(type: "subtitle", index: selectedIndex)
            openTouchTrackPanel()
            let choice = app.buttons["subtitle-\(selectedIndex)"]
            reveal(choice)
            XCTAssertEqual(choice.value as? String, "Selected")
            capture("External text \(tag) touch selected checkmark")
            activate(app.buttons["playerOptionsDone"])
            #endif
            XCTAssertTrue(method.waitForExistence(timeout: 10))
            XCTAssertEqual(method.label, "Direct play", "External text and Off must not renegotiate the existing A/V item to HLS")
            XCTAssertFalse(app.descendants(matching: .any).matching(identifier: "playbackWaiting").firstMatch.exists, "An optional text choice must not restart A/V readiness")
        }
        chooseExternalSubtitle("-1", tag: "01 Off before external")
        chooseExternalSubtitle(String(index), tag: "02 external before cue")
        Thread.sleep(forTimeInterval: 2)
        XCTAssertLessThanOrEqual(abs(seconds(position.label) - paused), 1, "Off then external must preserve paused clock and intent")
        print("F2_METRIC external_text_selected index=\(index) codec=\(codec) cue=\(cue)..<\(cueEnd) paused=\(paused) after=\(seconds(position.label)) method=\(method.label)")
        #if os(tvOS)
        XCUIRemote.shared.press(.playPause)
        // Native Resume dismisses Info. Observe AVKit's actual elapsed-time HUD
        // while playing; its former custom Info clock is no longer on screen.
        _ = try XCTUnwrap(waitForTVPlayerClock(identifier: "AXElapsedTime", minimum: cue, maximum: cueEnd - 1,
                                              timeout: TimeInterval(cue + 15), metric: "external_native_cue"),
                          "Resume naturally into the actual external cue interval")
        XCUIRemote.shared.press(.playPause)
        openNativeTVInfoTab()
        position = app.descendants(matching: .any).matching(identifier: "playbackPosition").firstMatch
        XCTAssertTrue(position.waitForExistence(timeout: 10))
        #else
        // Stay paused while a real slider gesture enters the short cue. AVKit UI
        // dispatch can consume the entire cue if Pause starts after its first frame.
        activate(app.buttons["playerSeek"])
        let cueSlider = app.sliders["Seek position"]
        XCTAssertTrue(cueSlider.waitForExistence(timeout: 10))
        let duration = try XCTUnwrap(config["directRunSeconds"].flatMap(Double.init))
        XCTAssertGreaterThan(duration, Double(cueEnd))
        let cueTarget = cue + (cueEnd - cue) / 2
        let currentSeekValue = seconds(cueSlider.value as? String ?? position.label)
        let sliderFrame = cueSlider.frame
        XCTAssertGreaterThan(sliderFrame.width, 40, "The real seek slider must have a usable touch target")
        XCTAssertTrue(cueSlider.isHittable)
        let thumbInset = min(12.0, sliderFrame.width * 0.05)
        let railWidth = max(1, sliderFrame.width - 2 * thumbInset)
        let startFraction = min(1, max(0, Double(currentSeekValue) / duration))
        let targetFraction = min(1, max(0, Double(cueTarget) / duration))
        let start = cueSlider.coordinate(withNormalizedOffset: CGVector(dx: (thumbInset + railWidth * startFraction) / sliderFrame.width, dy: 0.5))
        let target = cueSlider.coordinate(withNormalizedOffset: CGVector(dx: (thumbInset + railWidth * targetFraction) / sliderFrame.width, dy: 0.5))
        print("F2_GESTURE external_cue_slider frame=\(sliderFrame) start_seconds=\(currentSeekValue) target_seconds=\(cueTarget)")
        start.press(forDuration: 0.15, thenDragTo: target)
        let reached = waitForPausedClockNear(position, target: cueTarget, tolerance: 1, timeout: 15, metric: "external_paused_cue_seek")
        if !reached { captureTouchCueState("real seek did not reach the cue midpoint") }
        XCTAssertTrue(reached, "A slider label cannot substitute for the actual paused player reaching the cue")
        activate(app.buttons["playerOptionsDone"])
        #endif
        Thread.sleep(forTimeInterval: 1)
        let cuePosition = seconds(position.label)
        #if os(iOS)
        if !(cue..<cueEnd ~= cuePosition) { captureTouchCueState("actual paused clock outside configured cue") }
        #endif
        XCTAssertTrue(cue..<cueEnd ~= cuePosition, "The actual paused clock must still be inside the configured external cue")
        XCTAssertEqual(method.label, "Direct play")
        #if os(iOS)
        Thread.sleep(forTimeInterval: 2)
        let stableCuePosition = seconds(position.label)
        if stableCuePosition != cuePosition { captureTouchCueState("seek did not retain paused intent") }
        XCTAssertEqual(stableCuePosition, cuePosition, "The real cue seek must retain the paused clock before overlay comparison")
        #endif
        #if os(tvOS)
        captureNativeTVExternalSubtitlePreview("External text 03 ON actual overlay at \(cuePosition)s", pausedSeconds: cuePosition)
        position = app.descendants(matching: .any).matching(identifier: "playbackPosition").firstMatch
        #else
        capture("External text 03 ON actual overlay at \(cuePosition)s")
        #endif

        // The UILabel is intentionally not an accessibility element. Actual glyphs
        // and their disappearance are judged from these paired same-time frames.
        chooseExternalSubtitle("-1", tag: "04 Off at cue")
        Thread.sleep(forTimeInterval: 1)
        XCTAssertLessThanOrEqual(abs(seconds(position.label) - cuePosition), 1)
        #if os(tvOS)
        captureNativeTVExternalSubtitlePreview("External text 05 OFF same paused cue at \(seconds(position.label))s", pausedSeconds: cuePosition)
        position = app.descendants(matching: .any).matching(identifier: "playbackPosition").firstMatch
        #else
        capture("External text 05 OFF same paused cue at \(seconds(position.label))s")
        #endif
        chooseExternalSubtitle(String(index), tag: "06 external restored at cue")
        Thread.sleep(forTimeInterval: 1)
        XCTAssertLessThanOrEqual(abs(seconds(position.label) - cuePosition), 1)
        XCTAssertTrue(cue..<cueEnd ~= seconds(position.label))
        #if os(tvOS)
        captureNativeTVExternalSubtitlePreview("External text 07 ON restored actual overlay at \(seconds(position.label))s", pausedSeconds: cuePosition)
        position = app.descendants(matching: .any).matching(identifier: "playbackPosition").firstMatch
        #else
        capture("External text 07 ON restored actual overlay at \(seconds(position.label))s")
        #endif
        print("F2_METRIC external_text_cue_on_off_on index=\(index) clock=\(seconds(position.label)) method=\(method.label) window=\(cue)..<\(cueEnd)")
        #if os(tvOS)
        leaveNativeTVPlayer(returningTo: play)
        XCTAssertTrue(play.hasFocus, "Normal Menu should restore the original detail action after optional text changes")
        #else
        try touchNativeTransport("Play")
        XCTAssertTrue(waitForPlaybackClock(position, minimum: max(cueEnd + 1, cuePosition + 3), timeout: 30, metric: "external_resume_past_cue"),
                      "The actual native player must resume and advance beyond the external cue after On/Off/On")
        XCTAssertTrue(nativePlayer.exists)
        XCTAssertEqual(method.label, "Direct play")
        capture("External text resumed beyond actual cue")
        activate(app.buttons["closePlayer"])
        XCTAssertTrue(play.waitForExistence(timeout: 15))
        XCTAssertTrue(play.isHittable, "Normal Back must reveal the original usable detail action")
        #endif
        XCTAssertTrue(app.staticTexts["detailTitle"].exists)
        capture("External text 08 normal Back to detail")
    }
    func testTouchPositiveSubtitleSelectionAndCue() throws {
        #if os(iOS)
        ensureSignedIn()
        let subtitle = try XCTUnwrap(config["directSubtitleIndex"].flatMap(Int.init))
        let cue = try XCTUnwrap(config["directSubtitleCueSeconds"].flatMap(Int.init))
        XCTAssertGreaterThanOrEqual(subtitle, 0, "The fixture must provide a real positive subtitle stream, not Off (-1)")
        XCTAssertGreaterThan(cue, 0, "The fixture must identify a positive cue timestamp")
        if let codec = config["directSubtitleCodec"] {
            XCTAssertEqual(codec.lowercased(), "mov_text", "The positive caption fixture should use the known mov_text stream")
        }
        try openSampleDetail(id: XCTUnwrap(config["directID"]), key: "direct")
        let play = app.buttons["play"]
        reveal(play); activate(play)
        let method = app.staticTexts["playbackMethod"]
        XCTAssertTrue(method.waitForExistence(timeout: 30))
        XCTAssertTrue(waitForSettledMethod(method, timeout: 45), "Wait for Direct or HLS recovery to settle before the subtitle interaction")
        XCTAssertTrue(["Direct play", "Server HLS"].contains(method.label), "The caption flow may start direct or recover through HLS; the separate matrix owns strict Direct acceptance")
        print("F1_METRIC positive_subtitle_initial_method=\(method.label)")
        let position = app.staticTexts["playbackPosition"]
        XCTAssertTrue(position.waitForExistence(timeout: 10))
        XCTAssertTrue(waitForPlaybackClock(position, minimum: seconds(position.label) + 3, timeout: 40, metric: "positive_subtitle_initial"))

        // Pause first, then switch to the known positive mov_text stream. PlaybackSession must
        // retain paused intent as it renegotiates; resuming afterward must advance toward cue 21.
        let pause = app.buttons.matching(NSPredicate(format: "label CONTAINS[c] %@", "Pause")).firstMatch
        if !pause.exists { app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap() }
        XCTAssertTrue(pause.waitForExistence(timeout: 10), "AVKit should expose Pause before the paused track replacement check")
        activate(pause)
        let pausedAt = seconds(position.label)
        Thread.sleep(forTimeInterval: 2)
        XCTAssertLessThanOrEqual(seconds(position.label), pausedAt + 1, "The playback clock should remain paused before changing subtitles")

        selectTouchTrack(type: "subtitle", index: "\(subtitle)")
        let selected = app.buttons["subtitle-\(subtitle)"]
        openTouchTrackPanel()
        XCTAssertEqual(selected.value as? String, "Selected", "A positive subtitle stream must be selected, not Off")
        print("F1_AX positive_subtitle_index=\(subtitle) value=\(selected.value ?? "")")
        activate(app.buttons["playerOptionsDone"])
        expectation(for: NSPredicate(format: "label == %@", "Server HLS"), evaluatedWith: method)
        waitForExpectations(timeout: 30)
        Thread.sleep(forTimeInterval: 2)
        XCTAssertLessThanOrEqual(seconds(position.label), pausedAt + 1, "Track replacement should retain paused intent")
        print("F1_METRIC positive_subtitle_paused_position_before=\(pausedAt) after=\(seconds(position.label))")

        activate(app.buttons["playerSeek"])
        let seekPosition = app.sliders["Seek position"]
        XCTAssertTrue(seekPosition.waitForExistence(timeout: 10), "The Pictureshow Seek sheet should expose an accessible seek position")
        let duration = max(1, Double(config["directRunSeconds"] ?? "0") ?? 0)
        let currentSeekValue = seconds(seekPosition.value as? String ?? position.label)
        let sliderFrame = seekPosition.frame
        XCTAssertGreaterThan(sliderFrame.width, 40, "The labeled Pictureshow seek slider should have a real touch target")
        let thumbInset = min(12.0, sliderFrame.width * 0.05)
        let railWidth = max(1, sliderFrame.width - 2 * thumbInset)
        let startFraction = min(1, max(0, Double(currentSeekValue) / duration))
        let targetSeconds = min(duration, Double(cue))
        let targetFraction = min(1, max(0, targetSeconds / duration))
        let startX = thumbInset + railWidth * startFraction
        let targetX = thumbInset + railWidth * targetFraction
        let start = seekPosition.coordinate(withNormalizedOffset: CGVector(dx: startX / sliderFrame.width, dy: 0.5))
        let target = seekPosition.coordinate(withNormalizedOffset: CGVector(dx: targetX / sliderFrame.width, dy: 0.5))
        print("F1_GESTURE positive_subtitle_slider_drag frame=\(sliderFrame) start_seconds=\(currentSeekValue) target_seconds=\(targetSeconds) start_fraction=\(startFraction) target_fraction=\(targetFraction)")
        start.press(forDuration: 0.15, thenDragTo: target)
        let seekReadout = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "Seek to ")).firstMatch
        XCTAssertTrue(seekReadout.waitForExistence(timeout: 5), "The seek sheet should show its current Seek-to value after the first touch drag")
        let readoutStart = seekReadout.label.range(of: "Seek to ")!.upperBound
        let firstDragSeconds = seconds(String(seekReadout.label[readoutStart...]))
        XCTAssertGreaterThan(firstDragSeconds, 0, "The seek slider's visible Seek-to readout must expose the dragged time")
        XCTAssertGreaterThan(firstDragSeconds, currentSeekValue + 10, "The real touch drag should move the paused seek target forward")
        expectation(for: NSPredicate { _, _ in
            let value = self.seconds(position.label)
            return value >= firstDragSeconds - 1 && value <= firstDragSeconds + 2
        }, evaluatedWith: position)
        waitForExpectations(timeout: 20)
        print("F1_GESTURE positive_subtitle_touch_seek_seconds=\(firstDragSeconds) paused_clock=\(seconds(position.label))")

        // The first touch drag intentionally proves real gesture support, but its landing
        // point can differ by a few seconds from the normalized target. Use the accessible
        // Back 10 control for a bounded seek near 14s, then let the decoded video run through
        // the positive cue and pause on the observed 20–23s window.
        var expectedBackTarget = firstDragSeconds
        var backCount = 0
        var backSeconds = firstDragSeconds
        repeat {
            activate(app.buttons["seekBackward"])
            expectedBackTarget = max(0, expectedBackTarget - 10)
            let backReadoutStart = seekReadout.label.range(of: "Seek to ")!.upperBound
            backSeconds = seconds(String(seekReadout.label[backReadoutStart...]))
            XCTAssertLessThanOrEqual(abs(backSeconds - expectedBackTarget), 1, "Each Back 10 action should move the seek target by ten seconds")
            backCount += 1
        } while backSeconds > cue - 3 && backCount < 3
        XCTAssertLessThanOrEqual(backSeconds, cue - 3, "The paused seek target should be below the positive cue before resuming")
        print("F1_GESTURE positive_subtitle_back10_count=\(backCount) target_seconds=\(backSeconds)")
        activate(app.buttons["playerOptionsDone"])
        expectation(for: NSPredicate { _, _ in
            let value = self.seconds(position.label)
            return value >= backSeconds - 1 && value <= backSeconds + 2
        }, evaluatedWith: position)
        waitForExpectations(timeout: 20)
        let resume = app.buttons.matching(NSPredicate(format: "label CONTAINS[c] %@", "Play")).firstMatch
        XCTAssertTrue(resume.waitForExistence(timeout: 10), "AVKit should expose Play after the seek panel closes")
        activate(resume)
        expectation(for: NSPredicate { _, _ in
            let value = self.seconds(position.label)
            return value >= cue - 1 && value <= cue + 2
        }, evaluatedWith: position)
        waitForExpectations(timeout: 20)
        let pauseAtCue = app.buttons.matching(NSPredicate(format: "label CONTAINS[c] %@", "Pause")).firstMatch
        XCTAssertTrue(pauseAtCue.waitForExistence(timeout: 10), "AVKit should expose Pause after playback resumes")
        activate(pauseAtCue)
        Thread.sleep(forTimeInterval: 1)
        let cuePosition = seconds(position.label)
        XCTAssertTrue((cue - 1)...(cue + 2) ~= cuePosition, "Pause and capture only while the player clock is in the subtitle cue window")
        print("F1_METRIC positive_subtitle_paused_cue_position_seconds=\(cuePosition)")
        capture("iPhone positive mov_text subtitle paused cue at \(cue)s")
        activate(resume)
        XCTAssertTrue(waitForPlaybackClock(position, minimum: cue + 3, timeout: TimeInterval(cue + 30), metric: "positive_subtitle_after_cue"))
        activate(app.buttons["closePlayer"])
        XCTAssertTrue(app.staticTexts["detailTitle"].waitForExistence(timeout: 15), "A normal Back action should return to detail after subtitle renegotiation")
        #else
        throw XCTSkip("Positive touch subtitle selection is an iOS acceptance case")
        #endif
    }
    func testSeriesSeasonsEpisodeAndTrackControls() throws {
        ensureSignedIn()
        let seriesID = try XCTUnwrap(config["seriesID"])
        #if os(tvOS)
        if let libraryID = config["showsLibraryID"] {
            let library = app.buttons["item-\(libraryID)"].firstMatch
            reveal(library); activate(library)
            capture("TV shows library")
        }
        #endif
        let series = app.buttons["item-\(seriesID)"].firstMatch
        reveal(series); activate(series)
        XCTAssertTrue(app.staticTexts["detailTitle"].waitForExistence(timeout: 15))
        capture("Series detail")
        let seasonID = try XCTUnwrap(config["seasonID"])
        let episodeID = try XCTUnwrap(config["episodeID"])
        let season = app.buttons["season-\(seasonID)"]
        reveal(season); activate(season)
        let episode = app.buttons["item-\(episodeID)"]
        XCTAssertTrue(episode.waitForExistence(timeout: 10))
        capture("Season episodes")
        reveal(episode); activate(episode)
        XCTAssertTrue(app.staticTexts["detailTitle"].waitForExistence(timeout: 15))
        let play = app.buttons["play"]
        XCTAssertTrue(play.frame.width > 0 && play.frame.height > 0)
        XCTAssertTrue(app.frame.contains(play.frame), "The episode Play action must remain fully inside the visible viewport")
        capture("Series episode detail")
        reveal(play); activate(play)
        #if os(iOS)
        let audio = try XCTUnwrap(config["episodeAudioIndex"])
        let subtitle = try XCTUnwrap(config["episodeSubtitleIndex"])
        // A tap on the already-selected option must still apply-and-dismiss. Then exercise a
        // real change and restore the configured audio track for the subsequent playback check.
        selectTouchTrack(type: "audio", index: audio)
        selectTouchTrack(type: "audio", index: "-1")
        selectTouchTrack(type: "audio", index: audio)
        selectTouchTrack(type: "subtitle", index: subtitle)
        openTouchTrackPanel()
        XCTAssertEqual(app.buttons["subtitle-\(subtitle)"].value as? String, "Selected")
        capture("Audio and subtitle selection")
        activate(app.buttons["playerOptionsDone"])
        #endif
        #if os(tvOS)
        let nativePlayer = app.descendants(matching: .any).matching(identifier: "pictureshowNativePlayer").firstMatch
        XCTAssertTrue(nativePlayer.waitForExistence(timeout: 15), "The identified AVKit player should be visible")
        XCUIRemote.shared.press(.up)
        let position = app.descendants(matching: .any).matching(identifier: "AXElapsedTime").firstMatch
        XCTAssertTrue(position.waitForExistence(timeout: 30), "AVKit should expose an elapsed clock without opening its unlabeled custom info tab")
        let initial = seconds(position.label)
        XCTAssertTrue(waitForPlaybackClock(position, minimum: max(3, initial + 3), timeout: 45, metric: "episode_hls_native", nativeAVKitClock: true))
        capture("Episode Server HLS advancing native clock")

        XCUIRemote.shared.press(.playPause)
        let pausedClock = seconds(position.label)
        Thread.sleep(forTimeInterval: 2)
        XCTAssertLessThanOrEqual(seconds(position.label), pausedClock + 1, "Pause playback before track-menu navigation")
        var unexposedTrackSelection: [String] = []
        let audioIndex = try XCTUnwrap(config["episodeAudioIndex"])
        let subtitleIndex = try XCTUnwrap(config["episodeSubtitleIndex"])
        for (menuTitle, configuredIndex) in [("Audio", audioIndex), ("Subtitles", subtitleIndex)] {
            let indices = menuTitle == "Audio" && configuredIndex != "-1" ? ["-1", configuredIndex] : [configuredIndex]
            for index in indices {
                let choice = selectNativeTVMenuTrack(menuTitle: menuTitle, index: index)
                let selected = inspectNativeTVMenuSelection(menuTitle: menuTitle, index: index)
                capture("Native TV \(menuTitle) selected \(choice)")
                if !selected { unexposedTrackSelection.append("\(menuTitle) \(choice)") }
                XCUIRemote.shared.press(.menu)
                XCTAssertTrue(nativePlayer.waitForExistence(timeout: 5), "Closing a native track menu should leave the AVKit player open")
                let restored = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
                    self.seconds(position.label) >= max(0, pausedClock - 1)
                }, object: position)
                XCTAssertEqual(XCTWaiter.wait(for: [restored], timeout: 30), .completed,
                               "Track replacement should restore the paused position")
                let afterTrack = seconds(position.label)
                Thread.sleep(forTimeInterval: 2)
                XCTAssertLessThanOrEqual(seconds(position.label), afterTrack + 1,
                                         "Audio/subtitle replacement should preserve paused intent")
                print("F1_METRIC tv_track_paused clockBefore=\(pausedClock) afterApply=\(afterTrack) menu=\(menuTitle) index=\(index)")
            }
        }

        // Verify remote scrubbing from the playback position itself; tvOS AVKit does not
        // consistently publish a native Slider to XCTest on this presentation.
        XCUIRemote.shared.press(.up)
        let beforeForward = seconds(position.label)
        XCUIRemote.shared.press(.right, forDuration: 1.0)
        let forwardDeadline = ProcessInfo.processInfo.systemUptime + 12
        while ProcessInfo.processInfo.systemUptime < forwardDeadline && seconds(position.label) < beforeForward + 5 {
            Thread.sleep(forTimeInterval: 0.25)
        }
        let afterForward = seconds(position.label)
        print("F1_METRIC tv_native_right_seek before=\(beforeForward) after=\(afterForward)")
        XCTAssertGreaterThanOrEqual(afterForward, beforeForward + 5, "The native Right remote action should advance paused playback")
        capture("Native TV episode remote scrub forward")
        XCUIRemote.shared.press(.left, forDuration: 1.0)
        let backwardDeadline = ProcessInfo.processInfo.systemUptime + 12
        while ProcessInfo.processInfo.systemUptime < backwardDeadline && seconds(position.label) > afterForward - 5 {
            Thread.sleep(forTimeInterval: 0.25)
        }
        let afterBackward = seconds(position.label)
        print("F1_METRIC tv_native_left_seek afterForward=\(afterForward) after=\(afterBackward)")
        XCTAssertLessThanOrEqual(afterBackward, afterForward - 5, "The native Left remote action should return to an earlier position")
        capture("Native TV episode remote scrub backward")

        XCUIRemote.shared.press(.playPause)
        XCTAssertTrue(waitForPlaybackClock(position, minimum: seconds(position.label) + 3, timeout: 20,
                                           metric: "episode_hls_after_track_scrub", nativeAVKitClock: true))
        XCUIRemote.shared.press(.playPause)
        leaveNativeTVPlayer(returningTo: play)
        XCTAssertTrue(play.hasFocus, "Native Menu should return focus to the episode Play action")
        if !unexposedTrackSelection.isEmpty {
            XCTFail("Native AVKit selection state was not exposed for: \(unexposedTrackSelection.joined(separator: ", ")); the actual menu captures are retained for visual review")
        }
        #else
        let playbackMethod = app.staticTexts["playbackMethod"]
        let position = app.staticTexts["playbackPosition"]
        XCTAssertTrue(position.waitForExistence(timeout: 10), "The player should expose an accessible current position")
        let beforeChange = seconds(position.label)
        XCTAssertTrue(waitForPlaybackClock(position, minimum: beforeChange + 5, timeout: 35, metric: "episode"))
        capture("Episode playback after track apply")
        XCTAssertTrue(playbackMethod.waitForExistence(timeout: 30))
        XCTAssertEqual(playbackMethod.label, "Server HLS")
        selectTouchTrack(type: "audio", index: audio)
        selectTouchTrack(type: "subtitle", index: subtitle)
        activate(app.buttons["closePlayer"])
        XCTAssertTrue(play.waitForExistence(timeout: 15))
        #endif
    }
    func testTVPositiveSubtitleStreamAndCue() throws {
        #if os(tvOS)
        ensureSignedIn()
        _ = try openSampleDetail(id: XCTUnwrap(config["directID"]), key: "direct")
        capture("TV positive-caption movie detail")
        let play = app.buttons["startOver"]
        XCTAssertTrue(play.waitForExistence(timeout: 15), "The positive subtitle fixture must start from zero so cue timing is bounded")
        reveal(play); activate(play)
        let nativePlayer = app.descendants(matching: .any).matching(identifier: "pictureshowNativePlayer").firstMatch
        XCTAssertTrue(nativePlayer.waitForExistence(timeout: 15))
        let position = app.descendants(matching: .any).matching(identifier: "AXElapsedTime").firstMatch
        XCTAssertTrue(position.waitForExistence(timeout: 30))
        let initial = seconds(position.label)
        XCTAssertTrue(waitForPlaybackClock(position, minimum: max(2, initial + 2), timeout: 45,
                                           metric: "tv_positive_subtitle_initial", nativeAVKitClock: true))
        XCUIRemote.shared.press(.playPause)
        let paused = seconds(position.label)
        Thread.sleep(forTimeInterval: 2)
        XCTAssertLessThanOrEqual(seconds(position.label), paused + 1, "Pause before the positive subtitle replacement")

        let subtitleIndex = try XCTUnwrap(config["directSubtitleIndex"])
        XCTAssertNotEqual(subtitleIndex, "-1", "The fixture must provide a positive mov_text stream")
        let offLabel = selectNativeTVMenuTrack(menuTitle: "Subtitles", index: "-1")
        XCTAssertTrue(inspectNativeTVMenuSelection(menuTitle: "Subtitles", index: "-1"), "Turn subtitles Off before choosing the positive stream")
        capture("TV subtitles Off before positive stream")
        XCUIRemote.shared.press(.menu)
        XCTAssertTrue(nativePlayer.waitForExistence(timeout: 5), "Closing the Off menu should return to the same AVKit player")
        XCTAssertTrue(waitForPlaybackWaitingToClear(timeout: 45, metric: "tv_subtitle_off"),
                      "Do not accept the retained clock until the Off replacement has left its waiting state")
        XCTAssertTrue(waitForPausedClockNear(position, target: paused, tolerance: 1, timeout: 45,
                                             metric: "tv_subtitle_off_restore"), "Turning subtitles Off should restore the paused position after native readiness")
        let afterOff = seconds(position.label)
        Thread.sleep(forTimeInterval: 2)
        XCTAssertLessThanOrEqual(seconds(position.label), afterOff + 1, "Turning subtitles Off should preserve paused intent")
        XCTAssertGreaterThanOrEqual(seconds(position.label), max(0, afterOff - 1), "Turning subtitles Off should remain paused near the restored position")

        let label = selectNativeTVMenuTrack(menuTitle: "Subtitles", index: subtitleIndex)
        let selected = inspectNativeTVMenuSelection(menuTitle: "Subtitles", index: subtitleIndex)
        capture("TV positive subtitle stream selected")
        XCTAssertTrue(selected, "The native subtitle menu should expose the selected positive stream")
        XCUIRemote.shared.press(.menu)
        XCTAssertTrue(nativePlayer.waitForExistence(timeout: 5))
        XCTAssertTrue(waitForPlaybackWaitingToClear(timeout: 45, metric: "tv_positive_subtitle"),
                      "Do not accept the retained clock until the positive subtitle replacement has left its waiting state")
        XCTAssertTrue(waitForPausedClockNear(position, target: paused, tolerance: 1, timeout: 45,
                                             metric: "tv_positive_subtitle_restore"), "Subtitle replacement should restore the paused position after native readiness")
        Thread.sleep(forTimeInterval: 2)
        XCTAssertLessThanOrEqual(seconds(position.label), paused + 1, "Subtitle replacement should preserve paused intent")
        XCTAssertGreaterThanOrEqual(seconds(position.label), max(0, paused - 1), "Subtitle replacement should remain paused near the restored position")
        print("F1_METRIC tv_positive_subtitle_selected index=\(subtitleIndex) label=\(label) offLabel=\(offLabel) paused=\(paused)")

        XCUIRemote.shared.press(.playPause)
        let cue = try XCTUnwrap(config["directSubtitleCueSeconds"].flatMap(Int.init))
        XCTAssertTrue(waitForPlaybackClock(position, minimum: cue, timeout: 35,
                                           metric: "tv_positive_subtitle_cue", nativeAVKitClock: true))
        let cueClock = seconds(position.label)
        XCTAssertGreaterThanOrEqual(cueClock, cue)
        XCTAssertLessThanOrEqual(cueClock, cue + 2, "Capture the configured cue window, not a later timestamp")
        capture("TV positive subtitle cue at \(cueClock) seconds")
        leaveNativeTVPlayer(returningTo: play)
        XCTAssertTrue(play.hasFocus, "Menu should return from positive subtitle playback to the original detail")
        XCTAssertTrue(app.staticTexts["detailTitle"].exists)
        #endif
    }
    func testQuickConnectCodeAndCancel() throws {
        if hasAuthenticatedNavigationState() {
            returnHome()
            let signOut = app.buttons["signOut"]
            reveal(signOut)
            if signOut.waitForExistence(timeout: 5) { activate(signOut) }
        }
        let addServer = app.buttons["addServer"]
        if addServer.waitForExistence(timeout: 3) { activate(addServer) }
        let connect = app.buttons["connect"]
        if connect.waitForExistence(timeout: 3) { activate(connect) }
        XCTAssertTrue(app.buttons["quickConnect"].waitForExistence(timeout: 15))
        activate(app.buttons["quickConnect"])
        XCTAssertTrue(app.staticTexts["quickCode"].waitForExistence(timeout: 15))
        let code = app.staticTexts["quickCode"].label
        XCTAssertTrue(code.hasPrefix("Quick Connect code "))
        // The private harness reads the visible code from the live accessibility tree; do not
        // retain this one-time approval code in xcresult attachments or logs.
        if config["approveQuickConnect"] == "true" {
            XCTAssertTrue(hasHomeRoot(timeout: 90), "Approved Quick Connect should return to authenticated Home")
            returnHome()
        } else {
            activate(app.buttons["cancelQuick"])
            XCTAssertTrue(app.buttons["signIn"].waitForExistence(timeout: 10))
            XCTAssertTrue(app.staticTexts["quickCode"].waitForNonExistence(timeout: 10), "Cancellation should remove the one-time code")
        }
    }
    /// Real UI/lifecycle input around an explicitly held production metadata worker.
    /// A Play request before its first decision remains on Detail by design; the
    /// pre-source request rule is exercised at the cache boundary in hosted tests.
    /// This native case begins when the player first becomes reachable.
    @MainActor func testF4PhonePreparedMenuInitialSourceBackBackgroundAndReplacement() throws {
        #if os(iOS)
        let fixture = try NativeTrackHTTPFixture(durationSeconds: 120)
        let address = try fixture.start()
        defer { fixture.releaseAllMenuWorkers(); fixture.releasePlaybackInfo(); fixture.stop(); app.terminate() }
        app.launchEnvironment["PICTURESHOW_TEST_TRACK_MENU_GATE"] = address
        try fixture.launchSignedInDetail(app, activate: { self.activate($0) })
        let play = app.buttons["play"], tracks = app.buttons["playerTracks"]
        let done = app.buttons["playerOptionsDone"]
        func wait(_ message: String, _ condition: () -> Bool) {
            let deadline = ProcessInfo.processInfo.systemUptime + 15
            while !condition() && ProcessInfo.processInfo.systemUptime < deadline { Thread.sleep(forTimeInterval: 0.05) }
            XCTAssertTrue(condition(), message)
        }
        func workerCount(_ revision: Int) -> Int {
            fixture.snapshot.menuWorkerRequests.filter { $0.path == "/fixture-menu-worker/\(revision)/background" }.count
        }
        func assertNoPanel(_ message: String) {
            XCTAssertFalse(done.exists, message)
            XCTAssertFalse(app.buttons["audio-1"].exists, message)
        }
        activate(play)
        wait("The first source starts background preparation", { workerCount(1) == 1 })
        XCTAssertTrue(tracks.waitForExistence(timeout: 10))
        activate(tracks)
        XCTAssertEqual(tracks.value as? String, "Preparing audio and subtitles")
        assertNoPanel("The early request remains pending while real label work is blocked")
        fixture.releaseMenuWorker(1)
        XCTAssertTrue(done.waitForExistence(timeout: 10), "The initial request follows the first negotiated source and mounts on worker completion")
        XCTAssertTrue(app.buttons["audio-1"].waitForExistence(timeout: 5))
        activate(done)
        activate(app.buttons["closePlayer"])
        XCTAssertTrue(play.waitForExistence(timeout: 10))
        print("F4_NATIVE_PHONE firstSourceEarlyOpen=true completeMenu=true workerThread=background")

        // A second viewing holds a new worker. Back to details is usable while
        // it is blocked; completion cannot mount a sheet after the viewing exits.
        fixture.holdMenuWorker(1)
        let secondBefore = workerCount(1)
        activate(play)
        wait("The second viewing must independently start its worker", { workerCount(1) > secondBefore })
        activate(tracks)
        XCTAssertEqual(tracks.value as? String, "Preparing audio and subtitles")
        assertNoPanel("Early open cannot publish Off-only rows")
        activate(app.buttons["closePlayer"])
        XCTAssertTrue(play.waitForExistence(timeout: 10), "Back must remain responsive with the worker held")
        fixture.releaseMenuWorker(1)
        Thread.sleep(forTimeInterval: 0.3)
        assertNoPanel("A stopped viewing's worker must not reopen a panel")
        print("F4_NATIVE_PHONE pendingBack=true uiResponsiveWhileWorkerHeld=true unsolicitedOpen=false")

        // Deliver actual app background/foreground, rather than directly calling
        // the cache cancellation method used by the hosted state-machine tests.
        fixture.holdMenuWorker(1)
        let thirdBefore = workerCount(1)
        activate(play)
        wait("The third viewing must reach its held background worker", { workerCount(1) > thirdBefore })
        activate(tracks)
        XCTAssertEqual(tracks.value as? String, "Preparing audio and subtitles")
        XCUIDevice.shared.press(.home)
        XCTAssertTrue(app.wait(for: .runningBackground, timeout: 10) || app.state == .runningBackgroundSuspended)
        fixture.releaseMenuWorker(1)
        app.activate()
        XCTAssertTrue(tracks.waitForExistence(timeout: 10))
        assertNoPanel("Foregrounding cannot revive the background-cancelled pending open")
        XCTAssertNotEqual(tracks.value as? String, "Preparing audio and subtitles")
        print("F4_NATIVE_PHONE backgroundCancellation=true unsolicitedOpen=false")

        // Replace metadata through the real serialized selectTrack/reopen path.
        // The existing sheet must close and retain no right to select the source
        // that replaced it. New preparation is held to expose the handoff.
        activate(tracks)
        XCTAssertTrue(done.waitForExistence(timeout: 10))
        let retiredRow = app.buttons["audio-1"]
        XCTAssertTrue(retiredRow.exists)
        fixture.useReplacementSource()
        activate(app.buttons["fixtureReplaceTrackSourceInPanel"])
        wait("A replacement stream list must start generation 2", { workerCount(2) == 1 })
        XCTAssertTrue(done.waitForNonExistence(timeout: 10), "Source replacement closes through the native sheet scope")
        XCTAssertFalse(retiredRow.exists, "Captured old rows leave the active accessibility tree after handoff")
        XCTAssertTrue(tracks.isHittable, "The exact Audio and subtitles opener must be usable after handoff")
        let afterReplacement = fixture.snapshot.playbackInfoCount
        activate(tracks)
        XCTAssertEqual(tracks.value as? String, "Preparing audio and subtitles")
        // A second replacement cancels this pending open. Release the newest
        // worker first, then the obsolete one to expose stale publication bugs.
        fixture.useReplacementSource()
        activate(app.buttons["fixtureReplaceTrackSource"])
        wait("A newer replacement must independently prepare generation 3", { workerCount(3) == 1 })
        XCTAssertNotEqual(tracks.value as? String, "Preparing audio and subtitles")
        fixture.releaseMenuWorker(3)
        Thread.sleep(forTimeInterval: 0.3)
        fixture.releaseMenuWorker(2)
        Thread.sleep(forTimeInterval: 0.3)
        assertNoPanel("Neither current nor obsolete completion may revive the cancelled open")
        XCTAssertEqual(fixture.snapshot.playbackInfoCount, afterReplacement + 1,
                       "Stale menu work/picks may not issue an extra source negotiation")
        activate(tracks)
        XCTAssertTrue(done.waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["audio-1"].label.contains("replacement 2"), "The mounted rows must belong to the newest stream list")
        activate(done)
        XCTAssertFalse(fixture.snapshot.menuWorkerRequests.contains { $0.path.hasSuffix("/main") })
        print("F4_NATIVE_PHONE replacementHandoff=true pendingReplacementCancel=true newestRows=true staleCompletionRefused=true")
        fixture.useReplacementSource()
        activate(app.buttons["fixtureReplaceTrackSource"])
        wait("The next replacement starts a held generation before an error", { workerCount(4) == 1 })
        activate(tracks)
        XCTAssertEqual(tracks.value as? String, "Preparing audio and subtitles")
        fixture.failNextPlaybackInfo()
        activate(app.buttons["fixtureReplaceTrackSource"])
        XCTAssertTrue(app.staticTexts["Could not apply the selected tracks. Go back and try again."].waitForExistence(timeout: 15))
        fixture.releaseMenuWorker(4)
        Thread.sleep(forTimeInterval: 0.3)
        assertNoPanel("A real playback error cancels the pending request before worker completion")
        print("F4_NATIVE_PHONE playbackErrorCancellation=true unsolicitedOpen=false")
        #endif
    }

    @MainActor func testF4TVNativeEarlyMenuBackDoesNotReopenAfterWorkerCompletion() throws {
        #if os(tvOS)
        let fixture = try NativeTrackHTTPFixture(durationSeconds: 120)
        app.launchEnvironment["PICTURESHOW_TEST_TRACK_MENU_GATE"] = try fixture.start()
        defer { fixture.releaseAllMenuWorkers(); fixture.stop(); app.terminate() }
        try fixture.launchSignedInDetail(app, activate: { self.activate($0) })
        activate(app.buttons["play"])
        let deadline = ProcessInfo.processInfo.systemUptime + 15
        while fixture.snapshot.menuWorkerRequests.isEmpty && ProcessInfo.processInfo.systemUptime < deadline { Thread.sleep(forTimeInterval: 0.05) }
        XCTAssertEqual(fixture.snapshot.menuWorkerRequests.count, 1, "Metadata arrival starts the worker before a menu opens")
        XCTAssertTrue(fixture.snapshot.menuWorkerRequests[0].path.hasSuffix("/background"))
        // Decoder readiness is independent of menu work. Pause actual playback so
        // the finite media cannot end while the native chooser is exercised.
        while fixture.snapshot.startCount == 0 && ProcessInfo.processInfo.systemUptime < deadline { Thread.sleep(forTimeInterval: 0.05) }
        XCTAssertEqual(fixture.snapshot.startCount, 1)
        XCUIRemote.shared.press(.playPause)
        let trigger = nativeTVTransportMenu("Audio")
        activateNativeTVMenuChoice(trigger)
        let rowsBefore = try app.snapshot()
        XCTAssertFalse(tvSnapshotNodes(rowsBefore).contains { $0.elementType == .cell && $0.label.contains("Stream 12 ·") },
                       "A native early open must not mount partial or stale track rows")
        let cancel = app.buttons["cancelPendingTracks"]
        XCTAssertTrue(cancel.waitForExistence(timeout: 5), "The pending sheet needs a focusable cancel control")
        print("F4_PENDING_SHEET_FOCUS cancel=\(cancel.hasFocus)")
        XCUIRemote.shared.press(.menu)
        let dismissedDeadline = ProcessInfo.processInfo.systemUptime + 5
        while cancel.exists && ProcessInfo.processInfo.systemUptime < dismissedDeadline {
            Thread.sleep(forTimeInterval: 0.1)
        }
        XCTAssertFalse(cancel.exists, "Menu must dismiss the pending Tracks sheet before its worker finishes")
        fixture.releaseMenuWorker(1)
        Thread.sleep(forTimeInterval: 0.5)
        let after = try app.snapshot()
        XCTAssertFalse(tvSnapshotNodes(after).contains { $0.elementType == .cell && $0.hasFocus && $0.label.contains("Stream 12 ·") },
                       "Back-cancelled completion cannot summon a focused native menu")
        // A deliberate fresh open succeeds with all current rows. Entry focus
        // and the first-arrow/no-pullback behavior retain their independent F3 case.
        let selected = nativeTVTrackChoice(menuTitle: "Audio", index: String(NativeTrackHTTPFixture.selectedAudioIndex))
        XCTAssertTrue(selected.exists)
        XCTAssertEqual(fixture.snapshot.menuWorkerRequests.count, 1, "Fresh open reuses the worker's completed menus")
        XCTAssertEqual(fixture.snapshot.playbackInfoCount, 1)
        print("F4_NATIVE_TV workerThread=background pendingBack=true unsolicitedOpen=false freshOpenReuses=true")
        #endif
    }

    @MainActor func testF4TVNativeEarlyMenuMountsWhenWorkerCompletesWithoutReopening() throws {
        #if os(tvOS)
        let fixture = try NativeTrackHTTPFixture(durationSeconds: 120)
        app.launchEnvironment["PICTURESHOW_TEST_TRACK_MENU_GATE"] = try fixture.start()
        defer { fixture.releaseAllMenuWorkers(); fixture.stop(); app.terminate() }
        try fixture.launchSignedInDetail(app, activate: { self.activate($0) })
        activate(app.buttons["play"])
        let deadline = ProcessInfo.processInfo.systemUptime + 15
        while (fixture.snapshot.menuWorkerRequests.count != 1 || fixture.snapshot.startCount != 1)
                && ProcessInfo.processInfo.systemUptime < deadline { Thread.sleep(forTimeInterval: 0.05) }
        XCTAssertEqual(fixture.snapshot.menuWorkerRequests.count, 1)
        XCTAssertEqual(fixture.snapshot.startCount, 1)
        XCUIRemote.shared.press(.playPause)
        let trigger = nativeTVTransportMenu("Audio")
        activateNativeTVMenuChoice(trigger)
        let early = try app.snapshot()
        XCTAssertFalse(tvSnapshotNodes(early).contains { $0.elementType == .cell && nativeTVTrackLabel($0.label, matchesStream: "12") },
                       "An early native open cannot publish a partial stream row")

        // Keep the same native menu open while the real detached label worker
        // finishes. A second trigger activation would conceal a failed handoff.
        fixture.releaseMenuWorker(1)
        let byIdentifier = app.descendants(matching: .any).matching(identifier: "audio-12").firstMatch
        let byLabel = app.descendants(matching: .any).matching(NSPredicate(format: "label MATCHES[c] %@", "^(selected,\\s*)?Stream 12(\\s·|$).*" )).firstMatch
        let choice = byIdentifier.waitForExistence(timeout: 15) ? byIdentifier : byLabel
        XCTAssertTrue(choice.waitForExistence(timeout: 15), "The held native Audio panel must mount complete prepared rows after the worker finishes")
        XCTAssertTrue(nativeTVTrackLabel(choice.label, matchesStream: "12"))
        XCTAssertEqual(fixture.snapshot.menuWorkerRequests.count, 1, "One source preparation must serve the early native open")
        XCTAssertEqual(fixture.snapshot.playbackInfoCount, 1, "Completing the menu cannot renegotiate playback")
        print("F4_NATIVE_TV earlyOpenMountedWithoutReopen=true workerThread=background stream=12")
        #endif
    }

    @MainActor func testTVNativeSelectedTrackEntryThenArrowHasNoLatePullback() throws {
        #if os(tvOS)
        let fixture = try NativeTrackHTTPFixture(durationSeconds: 60)
        try fixture.start()
        defer { fixture.stop() }
        try fixture.launchSignedInDetail(app, activate: { self.activate($0) })
        defer { app.terminate() }
        activate(app.buttons["play"])
        let deadline = ProcessInfo.processInfo.systemUptime + 30
        while fixture.snapshot.startCount == 0 && ProcessInfo.processInfo.systemUptime < deadline { Thread.sleep(forTimeInterval: 0.1) }
        XCTAssertEqual(fixture.snapshot.startCount, 1, "The real AVPlayer must reach Ready and queue one Start")
        XCUIRemote.shared.press(.up)
        let nativePlayer = app.descendants(matching: .any).matching(identifier: "pictureshowNativePlayer").firstMatch
        XCTAssertTrue(nativePlayer.waitForExistence(timeout: 15))
        let elapsed = app.descendants(matching: .any).matching(identifier: "AXElapsedTime").firstMatch
        XCTAssertTrue(elapsed.waitForExistence(timeout: 10))
        let initial = seconds(elapsed.label)
        XCTAssertTrue(waitForPlaybackClock(elapsed, minimum: initial + 2, timeout: 30,
                                           metric: "native_track_menu_initial", nativeAVKitClock: true),
                      "A queued Start alone must not stand in for advancing native playback")
        let progressBeforePause = fixture.snapshot.progressCount
        XCUIRemote.shared.press(.playPause)
        let pauseDeadline = ProcessInfo.processInfo.systemUptime + 10
        while (fixture.snapshot.progressCount <= progressBeforePause
                || fixture.snapshot.requests.last(where: { $0.path == "/Sessions/Playing/Progress" })?.paused != true)
                && ProcessInfo.processInfo.systemUptime < pauseDeadline { Thread.sleep(forTimeInterval: 0.1) }
        XCTAssertGreaterThan(fixture.snapshot.progressCount, progressBeforePause, "The remote pause must produce a fresh Progress report")
        XCTAssertEqual(fixture.snapshot.requests.last(where: { $0.path == "/Sessions/Playing/Progress" })?.paused, true,
                       "The remote must establish reported paused intent before opening any track menu")
        let paused = seconds(elapsed.label)
        Thread.sleep(forTimeInterval: 1.2)
        XCTAssertLessThanOrEqual(seconds(elapsed.label), paused + 1)
        print("F3_NATIVE_MENU_PRECONDITION advancing=true remotePaused=true pausedClock=\(paused)")
        let before = fixture.snapshot
        func activeTrackRows(_ snapshot: XCUIElementSnapshot) throws -> [XCUIElementSnapshot] {
            // AVKit publishes an inactive source collection and the presented
            // menu with duplicate Stream N cells. Pick the collection owning
            // any focused track, independently of which index should be focused.
            let collections = tvSnapshotNodes(snapshot).filter { $0.elementType == .collectionView }
            let candidates = collections.map { $0.children.filter { $0.elementType == .cell } }.filter { rows in
                rows.contains { $0.label.hasPrefix("Stream ") || $0.label.hasPrefix("selected, Stream ") }
                    && rows.contains { $0.hasFocus }
            }
            XCTAssertEqual(candidates.count, 1, "Exactly one presented track menu must own native focus")
            print("F3_NATIVE_MENU_COLLECTION candidates=\(candidates.count) focusedRows=\(candidates.first?.filter { $0.hasFocus }.count ?? 0)")
            return try XCTUnwrap(candidates.count == 1 ? candidates.first : nil)
        }
        for (title, index) in [("Audio", NativeTrackHTTPFixture.selectedAudioIndex), ("Subtitles", NativeTrackHTTPFixture.selectedSubtitleIndex)] {
            // Opening the trigger is separate from resolving a row. AVKit can
            // expose duplicate inactive/live cells that defeat a live query even
            // while the presented focused menu is present in the native snapshot.
            let trigger = nativeTVTransportMenu(title)
            activateNativeTVMenuChoice(trigger)
            var entry = try app.snapshot()
            let entryDeadline = ProcessInfo.processInfo.systemUptime + 10
            func ownsTrackFocus(_ snapshot: XCUIElementSnapshot) -> Bool {
                tvSnapshotNodes(snapshot).contains { node in
                    node.elementType == .collectionView && node.children.contains { row in
                        row.elementType == .cell && row.hasFocus
                            && (row.label.hasPrefix("Stream ") || row.label.hasPrefix("selected, Stream "))
                    }
                }
            }
            while !ownsTrackFocus(entry) && ProcessInfo.processInfo.systemUptime < entryDeadline {
                Thread.sleep(forTimeInterval: 0.1)
                entry = try app.snapshot()
            }
            // Entry is judged before any row-navigation input. Settling above
            // depends only on some track receiving focus, never the expected index.
            let nodes = try activeTrackRows(entry)
            let prefix = title == "Audio" ? "audio-" : "subtitle-"
            func row(_ nodes: [XCUIElementSnapshot], _ index: Int) -> XCUIElementSnapshot? {
                nodes.first { $0.identifier == prefix + String(index) && $0.elementType == .cell }
                    ?? nodes.first { $0.elementType == .cell && nativeTVTrackLabel($0.label, matchesStream: String(index)) }
            }
            let selected = try XCTUnwrap(row(nodes, index), "The chosen middle row must exist in the native menu")
            XCTAssertTrue(selected.hasFocus, "The selected row must receive entry focus before any navigation")
            let value = (selected.value as? String ?? "").lowercased()
            XCTAssertTrue(selected.isSelected || ["selected", "on", "1"].contains(value) || selected.label.lowercased().hasPrefix("selected,"))
            capture("F3 native \(title) selected middle entry focus")
            XCUIRemote.shared.press(.down)
            let movedNodes = try activeTrackRows(app.snapshot())
            let moved = try XCTUnwrap(row(movedNodes, index + 1))
            XCTAssertTrue(moved.hasFocus, "One Down must move to the adjacent row")
            Thread.sleep(forTimeInterval: 1.3)
            let lateNodes = try activeTrackRows(app.snapshot())
            XCTAssertTrue(try XCTUnwrap(row(lateNodes, index + 1)).hasFocus, "Periodic updates cannot pull focus back to the selected row")
            XCTAssertFalse(try XCTUnwrap(row(lateNodes, index)).hasFocus)
            capture("F3 native \(title) no late focus pullback")
            print("F3_NATIVE_MENU title=\(title) selectedIndex=\(index) entryFocus=true firstDownIndex=\(index + 1) lateFocusRetained=true tracks=45")
            // Activate the already-selected row through the real remote path.
            // It must report intent without another negotiation or viewing.
            XCUIRemote.shared.press(.up)
            XCTAssertTrue(try XCTUnwrap(row(try activeTrackRows(app.snapshot()), index)).hasFocus)
            let progressBefore = fixture.snapshot.progressCount
            XCUIRemote.shared.press(.select)
            let reportDeadline = ProcessInfo.processInfo.systemUptime + 10
            while fixture.snapshot.progressCount == progressBefore && ProcessInfo.processInfo.systemUptime < reportDeadline { Thread.sleep(forTimeInterval: 0.1) }
            let progress = fixture.snapshot.requests.last { $0.path == "/Sessions/Playing/Progress" }
            XCTAssertGreaterThan(fixture.snapshot.progressCount, progressBefore)
            XCTAssertEqual(title == "Audio" ? progress?.audioIndex : progress?.subtitleIndex, index)
            XCTAssertEqual(progress?.paused, true)
            XCTAssertEqual(fixture.snapshot.playbackInfoCount, before.playbackInfoCount)
            XCTAssertEqual(fixture.snapshot.startCount, 1); XCTAssertEqual(fixture.snapshot.stoppedCount, 0)
            print("F3_NATIVE_SAME_PICK type=\(title) index=\(index) progress=true newPlaybackInfo=0 newViewing=0 paused=true")
        }
        let after = fixture.snapshot
        XCTAssertEqual(after.playbackInfoCount, before.playbackInfoCount)
        XCTAssertEqual(after.startCount, 1); XCTAssertEqual(after.stoppedCount, 0)
        XCTAssertEqual(after.subtitleRequestCount, before.subtitleRequestCount)
        #endif
    }
    @MainActor func testF5RealDemoRememberedSubtitleSurvivesCaptionPreference() throws {
        let itemID = try XCTUnwrap(config["captionID"])
        let index = try XCTUnwrap(config["rememberSubtitleIndex"])
        XCTAssertEqual(config["rememberSubtitleLanguage"], "spa")
        XCTAssertEqual(config["captionFallbackLanguage"], "eng", "The harness must prove a different caption fallback language")
        let originalDisplay = MACaptionAppearanceGetDisplayType(.user)
        defer { MACaptionAppearanceSetDisplayType(.user, originalDisplay) }
        MACaptionAppearanceSetDisplayType(.user, .automatic)
        ensureSignedIn()
        app.terminate(); app.launchEnvironment["PICTURESHOW_TEST_DETAIL_ID"] = itemID; app.launch()
        var play = app.buttons["play"]
        XCTAssertTrue(play.waitForExistence(timeout: 20)); activate(play)
        #if os(tvOS)
        _ = try XCTUnwrap(waitForTVPlayerClock(identifier: "AXElapsedTime", minimum: 2, maximum: 25, timeout: 90, metric: "f5_caption_initial"))
        XCUIRemote.shared.press(.playPause)
        _ = selectNativeTVMenuTrack(menuTitle: "Subtitles", index: index)
        leaveNativeTVPlayer(returningTo: play)
        #else
        var position = app.staticTexts["playbackPosition"]
        XCTAssertTrue(position.waitForExistence(timeout: 90))
        XCTAssertTrue(waitForPlaybackClock(position, minimum: 2, timeout: 90, metric: "f5_caption_initial"))
        let native = app.descendants(matching: .any).matching(identifier: "pictureshowNativePlayer").firstMatch
        let pause = native.buttons.matching(NSPredicate(format: "label ==[c] %@", "Pause")).firstMatch
        if !pause.isHittable { native.tap() }
        XCTAssertTrue(pause.waitForExistence(timeout: 5) && pause.isHittable)
        pause.tap()
        selectTouchTrack(type: "subtitle", index: index)
        activate(app.buttons["closePlayer"])
        XCTAssertTrue(play.waitForExistence(timeout: 20))
        #endif
        MACaptionAppearanceSetDisplayType(.user, .alwaysOn)
        XCTAssertEqual(MACaptionAppearanceGetDisplayType(.user), .alwaysOn)
        app.terminate(); app.launch()
        play = app.buttons["play"]
        XCTAssertTrue(play.waitForExistence(timeout: 20)); activate(play)
        #if os(tvOS)
        _ = try XCTUnwrap(waitForTVPlayerClock(identifier: "AXElapsedTime", minimum: 1, maximum: 30, timeout: 90, metric: "f5_caption_reopened"))
        XCUIRemote.shared.press(.playPause)
        let remembered = nativeTVTrackChoice(menuTitle: "Subtitles", index: index)
        let value = (remembered.value as? String ?? "").lowercased()
        XCTAssertTrue(remembered.isSelected || ["selected", "on", "1"].contains(value) || remembered.label.lowercased().hasPrefix("selected,"), "The server-remembered non-default-language track must already be selected without another pick")
        capture("F5 remembered language with system captions enabled")
        XCUIRemote.shared.press(.menu)
        leaveNativeTVPlayer(returningTo: play)
        #else
        position = app.staticTexts["playbackPosition"]
        XCTAssertTrue(position.waitForExistence(timeout: 90))
        XCTAssertTrue(waitForPlaybackClock(position, minimum: 1, timeout: 90, metric: "f5_caption_reopened"))
        openTouchTrackPanel()
        let remembered = app.buttons["subtitle-\(index)"]
        reveal(remembered)
        XCTAssertEqual(remembered.value as? String, "Selected", "No new pick may be needed after Stop/reopen")
        capture("F5 remembered language with system captions enabled")
        activate(app.buttons["playerOptionsDone"]); activate(app.buttons["closePlayer"])
        XCTAssertTrue(play.waitForExistence(timeout: 20))
        #endif
        print("F5_CAPTION_REOPEN selectedIndex=\(index) captionPreference=alwaysOn newPick=false")
    }

    /// Actual app/AVKit lifecycle against the private demo account. The harness
    /// additionally checks immutable HTTP report bodies and restores UserData.
    @MainActor func testF5RealDemoBackgroundReturnStaysPaused() throws {
        let itemID = try XCTUnwrap(config["seekID"])
        ensureSignedIn()
        app.terminate(); app.launchEnvironment["PICTURESHOW_TEST_DETAIL_ID"] = itemID; app.launch()
        let play = app.buttons["play"]
        XCTAssertTrue(play.waitForExistence(timeout: 20)); activate(play)
        #if os(tvOS)
        let first = try XCTUnwrap(waitForTVPlayerClock(identifier: "AXElapsedTime", minimum: 30, maximum: 80, timeout: 90, metric: "f5_background_started"))
        _ = try XCTUnwrap(waitForTVPlayerClock(identifier: "AXElapsedTime", minimum: first.seconds + 1, maximum: first.seconds + 12, timeout: 30, metric: "f5_background_decoding"))
        XCUIRemote.shared.press(.home)
        #else
        let position = app.staticTexts["playbackPosition"]
        XCTAssertTrue(position.waitForExistence(timeout: 90))
        XCTAssertTrue(waitForPlaybackClock(position, minimum: 31, timeout: 90, metric: "f5_background_decoding"))
        XCUIDevice.shared.press(.home)
        #endif
        Thread.sleep(forTimeInterval: 15)
        app.activate()
        #if os(tvOS)
        let returned = try XCTUnwrap(waitForTVPlayerClock(identifier: "AXElapsedTime", minimum: 30, maximum: 100, timeout: 30, metric: "f5_background_return"))
        Thread.sleep(forTimeInterval: 5)
        let later = try XCTUnwrap(waitForTVPlayerClock(identifier: "AXElapsedTime", minimum: returned.seconds, maximum: returned.seconds + 1, timeout: 15, metric: "f5_background_still_paused"))
        XCTAssertLessThanOrEqual(abs(later.seconds - returned.seconds), 1)
        capture("F5 real demo TV remains paused after Home return")
        XCUIRemote.shared.press(.playPause)
        _ = try XCTUnwrap(waitForTVPlayerClock(identifier: "AXElapsedTime", minimum: later.seconds + 2, maximum: later.seconds + 15, timeout: 30, metric: "f5_foreground_deliberate_play"))
        leaveNativeTVPlayer(returningTo: play)
        #else
        XCTAssertTrue(position.waitForExistence(timeout: 30))
        let returned = try XCTUnwrap(parsedClockSeconds(position.label))
        Thread.sleep(forTimeInterval: 5)
        let later = try XCTUnwrap(parsedClockSeconds(position.label))
        XCTAssertLessThanOrEqual(abs(later - returned), 1)
        capture("F5 real demo phone remains paused after Home return")
        let native = app.descendants(matching: .any).matching(identifier: "pictureshowNativePlayer").firstMatch
        let nativePlay = native.buttons.matching(NSPredicate(format: "label ==[c] %@", "Play")).firstMatch
        if !nativePlay.isHittable { native.tap() }
        XCTAssertTrue(nativePlay.waitForExistence(timeout: 5) && nativePlay.isHittable)
        nativePlay.tap()
        XCTAssertTrue(waitForPlaybackClock(position, minimum: later + 2, timeout: 30, metric: "f5_foreground_deliberate_play"))
        activate(app.buttons["closePlayer"])
        XCTAssertTrue(play.waitForExistence(timeout: 20))
        #endif
        print("F5_LIFECYCLE homeSeconds=15 returnStillPaused=true")
    }

    /// The real demo HLS decoder follows three remote activations of the
    /// Pictureshow Info panel's ten-second action. This is a separate control
    /// from AVKit clickpad navigation, which XCUIRemote cannot swipe.
    @MainActor func testF4TVRealDemoInfoSeekStepsKeepAbsoluteHLSClock() throws {
        #if os(tvOS)
        let seekID = try XCTUnwrap(config["seekID"])
        ensureSignedIn()
        let settings = app.buttons["openSettings"]
        reveal(settings); activate(settings)
        let bitrate = app.descendants(matching: .any).matching(identifier: "maxBitrate").firstMatch
        reveal(bitrate); activate(bitrate)
        let fourMbps = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", "4 Mbps")).firstMatch
        XCTAssertTrue(fourMbps.waitForExistence(timeout: 10))
        activate(fourMbps)
        returnHome()
        app.terminate()
        app.launchEnvironment["PICTURESHOW_TEST_DETAIL_ID"] = seekID
        app.launch()
        let play = app.buttons["play"]
        XCTAssertTrue(play.waitForExistence(timeout: 20))
        XCTAssertEqual(parsedClockSeconds(play.label.replacingOccurrences(of: "Resume from ", with: "")), 30)
        activate(play)
        let first = try XCTUnwrap(waitForTVPlayerClock(identifier: "AXElapsedTime", minimum: 30, maximum: 75,
                                                       timeout: 90, metric: "f4_info_hls_seeded_resume"))
        _ = try XCTUnwrap(waitForTVPlayerClock(identifier: "AXElapsedTime", minimum: first.seconds + 2,
                                               maximum: first.seconds + 12, timeout: 30,
                                               metric: "f4_info_hls_initial_decoder_advance"))
        XCUIRemote.shared.press(.playPause)
        let info = try XCTUnwrap(openNativeTVInfoTab())
        XCTAssertEqual(info.method, "Server HLS")
        let baseline = try XCTUnwrap(parsedClockSeconds(info.clock))
        let forward = app.buttons["seekForward"]
        XCTAssertTrue(forward.waitForExistence(timeout: 10))
        for index in 0..<3 {
            if index > 0 {
                let reopened = try XCTUnwrap(openNativeTVInfoTab())
                XCTAssertEqual(reopened.method, "Server HLS")
            }
            activateNativeTVInfoAction(identifier: "seekForward")
            let target = baseline + (index + 1) * 10
            let step = try XCTUnwrap(waitForTVPlayerClock(identifier: "AXElapsedTime", minimum: target - 1,
                                                          maximum: target + 1, timeout: 45,
                                                          metric: "f4_info_hls_step_\(index + 1)"))
            print("F4_TV_INFO_HLS_STEP index=\(index + 1) expectedSeconds=\(target) actualSeconds=\(step.seconds)")
        }
        let wanted = baseline + 30
        let settled = try XCTUnwrap(waitForTVPlayerClock(identifier: "AXElapsedTime", minimum: wanted - 1,
                                                         maximum: wanted + 1, timeout: 45,
                                                         metric: "f4_info_hls_three_steps"))
        print("F4_TV_INFO_HLS_SEEK baselineSeconds=\(baseline) expectedSeconds=\(wanted) actualSeconds=\(settled.seconds)")
        XCUIRemote.shared.press(.playPause)
        _ = try XCTUnwrap(waitForTVPlayerClock(identifier: "AXElapsedTime", minimum: wanted + 2,
                                               maximum: wanted + 10, timeout: 30,
                                               metric: "f4_info_hls_after_seek_decode"))
        XCUIRemote.shared.press(.playPause)
        leaveNativeTVPlayer(returningTo: play)
        #endif
    }
    /// Actual demo HLS, actual native timeline and remote key events. No seek
    /// helper buttons or pending-intent labels stand in for a committed A/V clock.
    @MainActor func testF4TVRealDemoRemoteSeekPacingCommitsEveryTenSecondStep() throws {
        #if os(tvOS)
        let seekID = try XCTUnwrap(config["seekID"], "Supply the own-pilot FPS Seek Clock 1440 demo item as private seekID")
        app.launchEnvironment["PICTURESHOW_SEEK_DIAGNOSTICS"] = "1"
        ensureSignedIn()
        // The 1440p60 source needs a bounded transcode on the demo server.
        // Set the viewer's actual preference before playback, then prove the
        // server honored it from the scoped encoder log after this case.
        let settings = app.buttons["openSettings"]
        reveal(settings); activate(settings)
        let bitrate = app.descendants(matching: .any).matching(identifier: "maxBitrate").firstMatch
        reveal(bitrate); activate(bitrate)
        let fourMbps = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", "4 Mbps")).firstMatch
        XCTAssertTrue(fourMbps.waitForExistence(timeout: 10), "The demo seek case needs the actual 4 Mbps Settings choice")
        activate(fourMbps)
        returnHome()
        app.terminate()
        app.launchEnvironment["PICTURESHOW_TEST_DETAIL_ID"] = seekID
        app.launch()
        let play = app.buttons["play"]
        XCTAssertTrue(play.waitForExistence(timeout: 20))
        let detailTitle = app.staticTexts["detailTitle"].label
        XCTAssertTrue(play.label.hasPrefix("Resume from "), "Root must seed this own-pilot item's 30-second resume before the case")
        XCTAssertEqual(parsedClockSeconds(play.label.replacingOccurrences(of: "Resume from ", with: "")), 30)
        activate(play)
        let first = try XCTUnwrap(waitForTVPlayerClock(identifier: "AXElapsedTime", minimum: 30, maximum: 75,
                                                      timeout: 45, metric: "f4_seek_seeded_resume"))
        _ = try XCTUnwrap(waitForTVPlayerClock(identifier: "AXElapsedTime", minimum: first.seconds + 2,
                                              maximum: first.seconds + 12, timeout: 30, metric: "f4_seek_initial_decoder_advance"),
                          "The real demo must decode and advance before testing remote seeks")
        XCUIRemote.shared.press(.playPause)

        func clock(_ metric: String) throws -> Int {
            XCUIRemote.shared.press(.up)
            let deadline = ProcessInfo.processInfo.systemUptime + 10
            while ProcessInfo.processInfo.systemUptime < deadline {
                if let snapshot = tvPlayerSnapshot(), let elapsed = snapshot.first(identifier: "AXElapsedTime"),
                   let value = parsedClockSeconds(elapsed.label), snapshot.first(identifier: "playbackWaiting") == nil { return value }
                Thread.sleep(forTimeInterval: 0.1)
            }
            XCTFail("The actual native A/V elapsed clock must be exposed for \(metric)")
            throw NSError(domain: "NativeSeekClockMissing", code: 1)
        }
        func focusTimeline() throws {
            XCUIRemote.shared.press(.up)
            // AVKit's TV scrub rail can expose Media Controls and an elapsed
            // marker as Other elements without any Slider in XCTest. The real
            // post-Select A/V clock below is the oracle for each remote step.
            let elapsed = app.descendants(matching: .any).matching(identifier: "AXElapsedTime").firstMatch
            XCTAssertTrue(elapsed.waitForExistence(timeout: 10), "The native AVKit timeline must expose elapsed time")
        }
        struct SeekResult {
            let name: String
            let expected: Int
            let actual: Int
            let stable: Bool
            let decoderAdvanced: Bool
            let onsets: [Double]
            let dispatchDurations: [Double]
            let scheduledGap: Double
        }
        var results: [SeekResult] = []
        let initialPaused = try clock("initial pause")
        Thread.sleep(forTimeInterval: 1.2)
        XCTAssertLessThanOrEqual(abs(try clock("initial pause stability") - initialPaused), 1,
                                 "Physical Play/Pause must establish paused playback before pacing")
        let information = try XCTUnwrap(openNativeTVInfoTab())
        XCTAssertEqual(information.method, "Server HLS", "The real high-frame-rate MKV demo should negotiate the server path")
        XCUIRemote.shared.press(.menu)
        let afterInfo = try clock("pause after Info")
        Thread.sleep(forTimeInterval: 1.2)
        XCTAssertLessThanOrEqual(abs(try clock("pause after Info stability") - afterInfo), 1,
                                 "Closing Info must preserve the paused viewing before remote steps")
        let scenarios: [(String, [XCUIRemote.Button], Double)] = [
            ("threeRightSpaced", [.right, .right, .right], 0.8),
            ("threeRightBurst", [.right, .right, .right], 0.1),
            ("mixedDirections", [.right, .left, .right, .right, .left], 0.1)
        ]
        for (name, directions, gap) in scenarios {
            try focusTimeline()
            // Read the native elapsed clock after focus, outside the paced input
            // sequence. No AX query or frame ingestion occurs between directions.
            let baselineSnapshot = try XCTUnwrap(tvPlayerSnapshot())
            let baseline = try XCTUnwrap(baselineSnapshot.first(identifier: "AXElapsedTime").flatMap { parsedClockSeconds($0.label) })
            let delta = directions.reduce(0) { $0 + ($1 == .right ? 10 : -10) }
            let expected = baseline + delta
            XCTAssertLessThan(expected, 165, "Every tested target must remain comfortably before the real 180-second end")
            var onsets: [Double] = [], durations: [Double] = []
            let scheduledStart = ProcessInfo.processInfo.systemUptime
            for (index, direction) in directions.enumerated() {
                let due = scheduledStart + Double(index) * gap
                let remaining = due - ProcessInfo.processInfo.systemUptime
                if remaining > 0 { Thread.sleep(forTimeInterval: remaining) }
                let onset = ProcessInfo.processInfo.systemUptime
                onsets.append(onset)
                XCUIRemote.shared.press(direction)
                durations.append(ProcessInfo.processInfo.systemUptime - onset)
            }
            // Native AVKit may keep directions in a preview until Select. Commit
            // once, then inspect its actual elapsed clock and decoder continuity.
            let commitAt = ProcessInfo.processInfo.systemUptime
            XCUIRemote.shared.press(.select)
            let settled = waitForTVPlayerClock(identifier: "AXElapsedTime", minimum: expected - 1, maximum: expected + 1,
                                                timeout: 20, metric: "f4_seek_" + name + "_committed")
            let actual = try settled?.seconds ?? clock(name + " final mismatch")
            Thread.sleep(forTimeInterval: 1.2)
            let afterHold = try clock(name + " pause hold")
            let stable = abs(afterHold - actual) <= 1
            let decoderAdvanced: Bool
            if stable {
                XCUIRemote.shared.press(.playPause)
                decoderAdvanced = waitForTVPlayerClock(identifier: "AXElapsedTime", minimum: actual + 2,
                                                       maximum: actual + 10, timeout: 25,
                                                       metric: "f4_seek_" + name + "_actual_decoder_advance") != nil
                XCUIRemote.shared.press(.playPause)
            } else {
                // Select unexpectedly resumed playback. Preserve that finding,
                // then establish pause before the next independent pacing case.
                decoderAdvanced = afterHold >= actual + 2
                XCUIRemote.shared.press(.playPause)
            }
            let receipt: [String: Any] = [
                "case": name, "baselineSeconds": baseline, "directionCount": directions.count,
                "directions": directions.map { $0 == .right ? "right" : "left" },
                "requestedGapMs": Int(gap * 1_000), "expectedSeconds": expected,
                "actualSeconds": actual, "afterPausedHoldSeconds": afterHold,
                "pausedIntentPreserved": stable, "actualDecoderAdvanced": decoderAdvanced,
                "dispatchOnsetUptimeMs": onsets.map { Int64($0 * 1_000) },
                "dispatchDurationMs": durations.map { Int($0 * 1_000) },
                "firstToLastDispatchOnsetMs": Int((onsets.last! - onsets.first!) * 1_000),
                "selectCommitUptimeMs": Int64(commitAt * 1_000),
                "clockProvider": "nativeAVKitElapsedAfterSelect", "appDeliveryTiming": "passiveNativeRemoteKeyDownLogs"
            ]
            let data = try JSONSerialization.data(withJSONObject: receipt, options: [.sortedKeys])
            print("F4_TV_REAL_SEEK " + String(decoding: data, as: UTF8.self))
            results.append(SeekResult(name: name, expected: expected, actual: actual, stable: stable,
                                      decoderAdvanced: decoderAdvanced, onsets: onsets, dispatchDurations: durations, scheduledGap: gap))
        }
        // Keep the original viewing throughout all three input sequences. Root's
        // scoped Start/Stop/negotiation receipts independently audit viewing identity.
        leaveNativeTVPlayer(returningTo: play)
        XCTAssertEqual(app.staticTexts["detailTitle"].label, detailTitle)
        XCTAssertTrue(play.hasFocus, "The original detail opener receives native Back focus")
        for result in results {
            XCTAssertLessThanOrEqual(abs(result.actual - result.expected), 1,
                                     "\(result.name): every delivered direction must commit exactly one ten-second step")
            XCTAssertTrue(result.stable, "\(result.name): a paused viewing must remain paused after native seek commitment")
            XCTAssertTrue(result.decoderAdvanced, "\(result.name): resumed decoder clock must advance from the committed target")
            let gaps = zip(result.onsets.dropFirst(), result.onsets).map { $0.0 - $0.1 }
            if result.name == "threeRightSpaced" {
                XCTAssertTrue(gaps.allSatisfy { (0.70...0.95).contains($0) },
                              "The requested 0.8-second spacing must actually be dispatched; measured onset gaps \(gaps)")
            } else if result.name == "threeRightBurst" {
                XCTAssertLessThanOrEqual(result.onsets.last! - result.onsets.first!, 0.3,
                                         "The fast case needs three dispatches within 0.3 seconds; XCTest idle overhead cannot stand in for that delivery")
            }
        }
        #endif
    }

    func testNativeTVDirectPlaybackMenusInfoAndRemoteScrub() throws {
        #if os(tvOS)
        ensureSignedIn()
        probeNativeTVHomeFocusOnce()
        try openSampleDetail(id: XCTUnwrap(config["directID"]), key: "direct")
        let play = app.buttons["play"]
        reveal(play); activate(play)
        openNativeTVInfoTab()
        let method = app.descendants(matching: .any).matching(identifier: "playbackMethod").firstMatch
        XCTAssertTrue(method.waitForExistence(timeout: 30))
        XCTAssertTrue(waitForSettledMethod(method, expected: "Direct play", timeout: 45), "Direct playback must settle before opening native track menus")
        let position = app.descendants(matching: .any).matching(identifier: "playbackPosition").firstMatch
        XCTAssertTrue(position.waitForExistence(timeout: 10))
        let before = seconds(position.label)
        expectation(for: NSPredicate { _, _ in self.seconds(position.label) >= before + 3 }, evaluatedWith: position)
        waitForExpectations(timeout: 35)
        capture("Native TV Direct play advancing before track selection")
        inspectNativeTVInfoAndTracks(preferDirect: true)
        XCTAssertEqual(method.label, "Server HLS", "An explicit native track selection should renegotiate to HLS")
        capture("Native TV player transport")
        if let cue = config["directSubtitleCueSeconds"].flatMap(Int.init) {
            XCUIRemote.shared.press(.playPause)
            let timeline = app.sliders.firstMatch
            XCTAssertTrue(timeline.waitForExistence(timeout: 10), "Native AVKit info should expose its timeline before the subtitle cue check")
            let duration = max(1, Double(config["directRunSeconds"] ?? "0") ?? 0)
            timeline.adjust(toNormalizedSliderPosition: CGFloat(max(0, min(1, Double(cue) / duration))))
            expectation(for: NSPredicate { _, _ in let value = self.seconds(position.label); return value >= cue - 1 && value <= cue + 2 }, evaluatedWith: position)
            waitForExpectations(timeout: 20)
            capture("Native TV selected subtitle paused cue at \(cue)s")
            XCUIRemote.shared.press(.playPause)
            XCTAssertTrue(waitForPlaybackClock(position, minimum: cue + 3, timeout: 30, metric: "tv_positive_subtitle_resume"))
        }

        // The Pictureshow info tab remains selected after track inspection. Move focus onto the
        // actual native AVKit timeline before sending remote left/right; delivered button events
        // alone do not prove that AVKit scrubbed playback.
        let timeline = app.sliders.firstMatch
        XCTAssertTrue(timeline.waitForExistence(timeout: 10), "Native AVKit should expose its timeline slider")
        for _ in 0..<8 where !timeline.hasFocus { XCUIRemote.shared.press(.down) }
        XCTAssertTrue(timeline.hasFocus, "Focus must be on the native AVKit timeline before remote scrubbing")
        let beforeScrub = seconds(position.label)
        XCUIRemote.shared.press(.right, forDuration: 2.0)
        expectation(for: NSPredicate { _, _ in self.seconds(position.label) >= beforeScrub + 5 }, evaluatedWith: position)
        waitForExpectations(timeout: 20)
        capture("Native TV remote scrub forward")
        XCUIRemote.shared.press(.left, forDuration: 1.0)
        expectation(for: NSPredicate { _, _ in self.seconds(position.label) <= beforeScrub + 3 }, evaluatedWith: position)
        waitForExpectations(timeout: 20)
        capture("Native TV remote scrub backward")

        // Menu on the native player is the advertised stop/back action. Verify the app returns
        // to detail, and avoid inferring success from remote-event delivery alone.
        leaveNativeTVPlayer(returningTo: play)
        XCTAssertTrue(play.hasFocus, "Native AVKit Menu should return focus to the original detail Play action")
        #endif
    }
    /// Keeps native elapsed-clock, remote pause/resume, and Menu-to-detail focus acceptance
    /// independent of the custom Pictureshow info tab's unlabeled AVKit cell. The fixture's
    /// negotiated transcode method is audited in sanitized Playback logs for this run.
    func testNativeTVConfiguredTranscodeClockPauseAndBackFocus() throws {
        #if os(tvOS)
        ensureSignedIn()
        _ = try openSampleDetail(id: XCTUnwrap(config["hlsID"]), key: "hls")
        let play = app.buttons["play"]
        reveal(play); activate(play)
        XCUIRemote.shared.press(.up)
        let elapsed = app.descendants(matching: .any).matching(identifier: "AXElapsedTime").firstMatch
        XCTAssertTrue(elapsed.waitForExistence(timeout: 30), "Native AVKit should expose elapsed time for the HLS fixture")
        let first = seconds(elapsed.label)
        XCTAssertTrue(waitForPlaybackClock(elapsed, minimum: max(3, first + 3), timeout: 45, metric: "tv_hls_native_initial", nativeAVKitClock: true))
        capture("Native TV HLS advancing elapsed-time clock")

        XCUIRemote.shared.press(.playPause)
        let paused = seconds(elapsed.label)
        Thread.sleep(forTimeInterval: 2)
        XCTAssertLessThanOrEqual(seconds(elapsed.label), paused + 1, "Native remote Play/Pause should stop the AVKit elapsed-time clock")
        capture("Native TV HLS paused by remote")
        XCUIRemote.shared.press(.playPause)
        XCTAssertTrue(waitForPlaybackClock(elapsed, minimum: paused + 3, timeout: 30, metric: "tv_hls_native_resumed", nativeAVKitClock: true))
        capture("Native TV HLS elapsed time after remote resume")

        let lastClock = seconds(elapsed.label)
        XCTAssertGreaterThan(lastClock, paused, "The native clock should have advanced after remote resume")
        leaveNativeTVPlayer(returningTo: play)
        XCTAssertTrue(play.hasFocus, "Native Menu should return to HLS detail and restore its Play action focus")
        capture("Native TV HLS returned to detail")
        XCTAssertTrue(play.isHittable, "The native player should be dismissed before returning to detail actions")
        XCTAssertTrue(app.staticTexts["detailTitle"].exists, "The native player should close back to the same HLS detail")
        #endif
    }
    /// Crosses a Jellyfin episode boundary through the native contextual action, so the
    /// following item must receive a new decision/source while the prior viewing stops once.
    func testNativeTVManualNextStartsAdjacentEpisodeSource() throws {
        #if os(tvOS)
        guard let nextID = config["nextEpisodeID"], !nextID.isEmpty,
              let currentSource = config["episodeMediaSourceID"], !currentSource.isEmpty,
              let nextSource = config["nextEpisodeMediaSourceID"], !nextSource.isEmpty,
              currentSource != nextSource else {
            throw XCTSkip("The private demo fixture has no adjacent episode with a distinct source")
        }
        ensureSignedIn()
        try openSeriesEpisodeDetail()
        let play = try zeroPositionPlayAction()
        reveal(play); activate(play)
        let nativePlayer = app.descendants(matching: .any).matching(identifier: "pictureshowNativePlayer").firstMatch
        XCTAssertTrue(nativePlayer.waitForExistence(timeout: 15))
        let initial = try XCTUnwrap(waitForTVPlayerClock(identifier: "AXElapsedTime", minimum: 0, timeout: 30,
                                                        metric: "tv_episode_next_initial_clock"))
        _ = try XCTUnwrap(waitForTVPlayerClock(identifier: "AXElapsedTime", minimum: max(3, initial.seconds + 3), timeout: 45,
                                              metric: "tv_episode_next_initial"), "The current episode must genuinely advance before manual Next")
        let duration = try XCTUnwrap(config["episodeRunSeconds"].flatMap(Int.init))
        XCTAssertGreaterThan(duration, 0, "The adjacent episode test needs a known duration")
        let currentName = try XCTUnwrap(config["episodeName"])
        let expectedName = try XCTUnwrap(config["nextEpisodeName"])
        let nearEndTarget = max(1, duration - 18)
        let nearEnd = try XCTUnwrap(waitForTVPlayerClock(identifier: "AXElapsedTime", minimum: nearEndTarget, maximum: duration - 5,
                                                        timeout: TimeInterval(duration + 15), metric: "tv_episode_next_natural_final_window"),
                                    "The current episode must naturally reach the manual-action window before physical EOS").seconds
        let nextActionPattern = "^Play next: \(NSRegularExpression.escapedPattern(for: expectedName)) \\([0-9]{1,2}\\)$"
        let nextByID = app.descendants(matching: .any).matching(identifier: "playNextTransport").firstMatch
        let nextByTitle = app.descendants(matching: .any).matching(NSPredicate(format: "label MATCHES %@", nextActionPattern)).firstMatch
        func nextInSnapshot(_ snapshot: TVPlayerSnapshot) -> XCUIElementSnapshot? {
            let matches = snapshot.nodes.filter {
                $0.label.range(of: nextActionPattern, options: .regularExpression) != nil && $0.isEnabled
            }
            let transport = matches.filter { $0.identifier == "playNextTransport" }
            let choices = transport.isEmpty ? matches : transport
            guard choices.count == 1,
                  let digits = choices[0].label.split(separator: "(").last?.dropLast(),
                  let countdown = Int(digits), countdown > 0 else { return nil }
            return choices[0]
        }
        func currentEpisodeAction(_ snapshot: TVPlayerSnapshot) -> (clock: Int, next: XCUIElementSnapshot)? {
            guard snapshot.nodes.contains(where: { $0.label == currentName }),
                  let clock = snapshot.first(identifier: "AXElapsedTime").flatMap({ parsedClockSeconds($0.label) }),
                  clock >= nearEndTarget, clock < duration - 3,
                  let next = nextInSnapshot(snapshot) else { return nil }
            return (clock, next)
        }
        // Every candidate requires the current title, valid native clock and exact
        // adjacent action inside one player-frame snapshot. Hittability is also
        // checked on the real control before taking a fresh final snapshot.
        var next: XCUIElement?
        let actionDeadline = ProcessInfo.processInfo.systemUptime + TimeInterval(max(1, duration - nearEnd - 3))
        var lastReveal = ProcessInfo.processInfo.systemUptime - 2
        while ProcessInfo.processInfo.systemUptime < actionDeadline {
            if ProcessInfo.processInfo.systemUptime - lastReveal >= 2 {
                XCUIRemote.shared.press(.up)
                lastReveal = ProcessInfo.processInfo.systemUptime
            }
            if let snapshot = tvPlayerSnapshot(), let candidate = currentEpisodeAction(snapshot) {
                let control = candidate.next.identifier == "playNextTransport" ? nextByID : nextByTitle
                if control.isHittable { next = control; break }
            }
            Thread.sleep(forTimeInterval: 0.35)
        }
        let nextAction = try XCTUnwrap(next, "AVKit must expose a hittable exact adjacent-episode action before physical EOS")
        capture("Native TV manual Next available before activation")
        captureFullScreen("Native TV complete credits surface before manual activation")
        XCTAssertTrue(nextAction.isHittable, "Manual Next must remain hittable after the evidence capture")
        let action = try XCTUnwrap(tvPlayerSnapshot().flatMap(currentEpisodeAction),
                                  "The current episode title, clock and exact Next action must still coexist after capture")
        XCTAssertLessThan(action.clock, duration - 3, "The original episode must still be playing before manual Next is activated")
        print("F1_METRIC tv_manual_next_action clock=\(action.clock) remaining=\(duration - action.clock) label=\(action.next.label) snapshot=true")
        activateNativeTVMenuChoice(nextAction)

        let reset = try XCTUnwrap(waitForTVPlayerClock(identifier: "AXElapsedTime", minimum: 0, maximum: nearEnd - 11,
                                                      timeout: 45, metric: "tv_episode_next_reset", requiredTitle: expectedName),
                                  "The adjacent title and reset native clock must coexist after manual handoff")
        let advanced = try XCTUnwrap(waitForTVPlayerClock(identifier: "AXElapsedTime", minimum: max(2, reset.seconds + 2), maximum: 12,
                                                         timeout: 35, metric: "tv_episode_next_advanced", requiredTitle: expectedName),
                                     "The adjacent episode must advance from its own beginning under its exact title")
        XCTAssertLessThanOrEqual(advanced.seconds, 12, "The next episode must not inherit the prior source position")
        capture("Native TV adjacent episode started")
        captureFullScreen("Native TV complete adjacent episode surface")
        leaveNativeTVPlayer(returningTo: play)
        #endif
    }
    #if os(tvOS)
    /// Pixel acceptance is supplied separately by the visual review. This case
    /// lets the native HUD hide so its contextual credits surface can be captured.
    func testNativeTVContextualContinuationPresentation() throws {
        ensureSignedIn()
        app.terminate()
        app.launchEnvironment["PICTURESHOW_TEST_DETAIL_ID"] = try XCTUnwrap(config["episodeID"])
        app.launch()
        let play = try zeroPositionPlayAction()
        reveal(play)
        let playRequestedAt = ProcessInfo.processInfo.systemUptime
        activate(play)
        let duration = try XCTUnwrap(config["episodeRunSeconds"].flatMap(Int.init))
        XCTAssertGreaterThan(duration, 20)
        let originalTitle = try XCTUnwrap(config["episodeName"])
        let anchor = try XCTUnwrap(waitForTVPlayerClock(identifier: "AXElapsedTime", minimum: 2, maximum: 12,
                                                       timeout: 30, metric: "tv_contextual_playback_anchor",
                                                       requiredTitle: originalTitle))
        let anchorAt = ProcessInfo.processInfo.systemUptime
        let exitDeadline = min(playRequestedAt + TimeInterval(duration - 4),
                               anchorAt + TimeInterval(duration - anchor.seconds - 4))
        // This is a timed visual-capture procedure. Hidden HUD clocks and
        // contextual AX roles are not pixel oracles. The backend binds these
        // timestamps to the original viewing; Opus judges the actual surface.
        for plannedClock in [duration - 14, duration - 12, duration - 10, duration - 8] {
            let plannedAt = anchorAt + TimeInterval(plannedClock - anchor.seconds)
            while ProcessInfo.processInfo.systemUptime < plannedAt {
                Thread.sleep(forTimeInterval: min(0.15, max(0, plannedAt - ProcessInfo.processInfo.systemUptime)))
            }
            guard ProcessInfo.processInfo.systemUptime < exitDeadline,
                  ProcessInfo.processInfo.systemUptime <= plannedAt + 1,
                  let player = tvPlayerSnapshot(), player.first(identifier: "pictureshowNativePlayer") != nil else {
                XCTFail("The contextual capture procedure must retain the player and its bounded pre-end schedule")
                return
            }
            print("F2_TV_CONTEXTUAL_CAPTURE phase=begin plannedClock=\(plannedClock) anchorClock=\(anchor.seconds) anchorMonotonic=\(anchorAt) monotonic=\(ProcessInfo.processInfo.systemUptime) utc=\(Date().timeIntervalSince1970)")
            captureFullScreen("Native TV contextual visual probe planned \(plannedClock) seconds")
            print("F2_TV_CONTEXTUAL_CAPTURE phase=end plannedClock=\(plannedClock) monotonic=\(ProcessInfo.processInfo.systemUptime) utc=\(Date().timeIntervalSince1970)")
            guard ProcessInfo.processInfo.systemUptime < exitDeadline,
                  ProcessInfo.processInfo.systemUptime <= plannedAt + 1.5 else {
                XCTFail("Each contextual visual probe must finish before its pre-end capture deadline")
                return
            }
            if let diagnostic = tvPlayerSnapshot() {
                let matches = diagnostic.nodes.filter {
                    ["playNext", "cancelNext", "playNextTransport", "cancelNextTransport"].contains($0.identifier)
                        || $0.label.hasPrefix("Next (") || $0.label.hasPrefix("Play next:")
                        || $0.label == "Cancel" || $0.label.hasPrefix("Cancel next")
                }
                print("F2_TV_CONTEXTUAL_AX plannedClock=\(plannedClock) matches=\(matches.count) diagnosticOnly=true")
                for node in matches.prefix(12) {
                    print("F2_TV_CONTEXTUAL_NODE type=\(node.elementType.rawValue) id=\(node.identifier) label=\(node.label) frame=\(node.frame)")
                }
            }
        }
        // The visual assertion is now captured during playback. Pause with the
        // remote before cleanup so Back transitions cannot cross physical EOS.
        XCUIRemote.shared.press(.playPause)
        leaveNativeTVPlayer(returningTo: play)
    }
    #endif
    /// Fresh items expose Play; resumable items additionally expose Play from start.
    /// Never use Resume as the zero-position decoder/continuation control.
    private func zeroPositionPlayAction() throws -> XCUIElement {
        let primary = app.buttons["play"]
        XCTAssertTrue(primary.waitForExistence(timeout: 15), "The authoritative primary action must be ready")
        let fromStart = app.buttons["startOver"]
        if fromStart.exists { return fromStart }
        XCTAssertEqual(primary.label, "Play", "Without Play from start, primary Play must have no saved resume")
        guard primary.label == "Play" else { throw NSError(domain: "Nonzero startup fixture", code: 1) }
        return primary
    }
    private func playAndResume(id: String, expected: String, key: String) throws {
        let containers = (config[key + "Containers"] ?? "").split(separator: "|").map(String.init)
        let button = try openSampleDetail(id: id, key: key)
        let play = app.buttons["play"]
        reveal(play)
        XCTAssertTrue(play.waitForExistence(timeout: 15))
        activate(play)
        let method = app.descendants(matching: .any).matching(identifier: "playbackMethod").firstMatch
        #if os(tvOS)
        let isNativeTranscodeClock = key != "direct"
        let position: XCUIElement
        if key == "direct" {
            openNativeTVInfoTab()
            XCTAssertTrue(method.waitForExistence(timeout: 30))
            XCTAssertTrue(waitForSettledMethod(method, expected: expected, timeout: 45), "The playback method should settle to \(expected)")
            position = app.descendants(matching: .any).matching(identifier: "playbackPosition").firstMatch
        } else {
            // For negotiated server transcodes, the system AVKit elapsed clock stays exposed
            // while the custom info cell has no AX focus. The sanitized Playback event records
            // the selected transcode method; the eligible fixture below proves saved resume.
            XCUIRemote.shared.press(.up)
            position = app.descendants(matching: .any).matching(identifier: "AXElapsedTime").firstMatch
        }
        XCTAssertTrue(position.waitForExistence(timeout: 30), "The native player should expose an accessibility clock")
        #else
        let isNativeTranscodeClock = false
        let position = app.staticTexts["playbackPosition"]
        XCTAssertTrue(method.waitForExistence(timeout: 30))
        XCTAssertTrue(waitForSettledMethod(method, expected: expected, timeout: 45), "The playback method should settle to \(expected)")
        XCTAssertTrue(position.waitForExistence(timeout: 10), "The player should expose an accessible current position")
        #endif
        capture("\(expected) initial preparation")
        XCTAssertTrue(waitForPlaybackClock(position, minimum: 2, timeout: 35, metric: key, nativeAVKitClock: isNativeTranscodeClock))
        // Respect the server's minimum video duration and watched percentage for resume.
        let minimum = max(Int(config[key + "ResumeSeconds"] ?? "6") ?? 6, seconds(position.label) + 6)
        XCTAssertTrue(waitForPlaybackClock(position, minimum: minimum, timeout: TimeInterval(minimum + 35), metric: key + "_resume", nativeAVKitClock: isNativeTranscodeClock))
        #if os(iOS)
        XCTAssertTrue(app.buttons["playerTracks"].exists)
        #endif
        capture("\(expected) playing")
        #if os(tvOS)
        leaveNativeTVPlayer(returningTo: play)
        #else
        activate(app.buttons["closePlayer"])
        #endif
        XCTAssertTrue(play.waitForExistence(timeout: 15))
        let eligible = config[key + "ResumeEligible"] != "false"
        var savedResumeSeconds = 0
        if eligible {
            expectation(for: NSPredicate(format: "label BEGINSWITH %@", "Resume from "), evaluatedWith: play)
            waitForExpectations(timeout: 15)
            let savedLabel = play.label
            let prefix = "Resume from "
            guard savedLabel.hasPrefix(prefix) else {
                XCTFail("The eligible resume fixture must expose a Resume from timestamp, got: \(savedLabel)")
                return
            }
            let rawTime = String(savedLabel.dropFirst(prefix.count))
            let timeComponents = rawTime.split(separator: ":", omittingEmptySubsequences: false)
            let fields = timeComponents.compactMap { Int($0) }
            guard fields.count == timeComponents.count,
                  fields.count == 2 || fields.count == 3,
                  fields.allSatisfy({ $0 >= 0 && $0 < 60 }),
                  let first = fields.first else {
                XCTFail("The eligible resume label must contain valid mm:ss or hh:mm:ss components: \(savedLabel)")
                return
            }
            savedResumeSeconds = fields.count == 2 ? first * 60 + fields[1] : first * 3600 + fields[1] * 60 + fields[2]
            XCTAssertGreaterThan(savedResumeSeconds, 0, "The saved resume timestamp must be positive")
        }
        #if os(tvOS)
        XCTAssertTrue(play.hasFocus, "Back should restore focus to the detail Play action")
        #endif
        let saved = play.label
        activate(play)
        #if os(tvOS)
        if key == "direct" {
            XCTAssertTrue(method.waitForExistence(timeout: 30))
            openNativeTVInfoTab()
        } else {
            let nativePlayer = app.descendants(matching: .any).matching(identifier: "pictureshowNativePlayer").firstMatch
            XCTAssertTrue(nativePlayer.waitForExistence(timeout: 30))
            XCUIRemote.shared.press(.up)
        }
        XCTAssertTrue(position.waitForExistence(timeout: 30), "The reopened native player should expose its clock")
        #else
        XCTAssertTrue(method.waitForExistence(timeout: 30))
        #endif
        let resumeMinimum = eligible ? savedResumeSeconds + 2 : 2
        // A reopened HLS stream has a 45-second startup deadline; allow 15 more seconds for
        // AVKit's status and accessible clock to surface. Keep the earlier 30-second failure
        // artifacts separate, and retain this finite 60-second metric.
        let resumedTimeout: TimeInterval = key == "hls" ? 60 : 30
        let resumed = waitForPlaybackClock(position, minimum: resumeMinimum, timeout: resumedTimeout, metric: key + "_resumed", nativeAVKitClock: isNativeTranscodeClock)
        if !resumed && key == "hls" { capture("HLS replay state after bounded 60-second wait") }
        XCTAssertTrue(resumed)
        capture("\(expected) \(eligible ? "resumed" : "replayed short sample") after \(saved)")
        if key == "direct", let audio = config["directAudioIndex"], let subtitle = config["directSubtitleIndex"] {
            #if os(tvOS)
            _ = audio; _ = subtitle
            inspectNativeTVInfoAndTracks(preferDirect: true)
            let beforeChange = seconds(position.label)
            XCTAssertTrue(waitForPlaybackClock(position, minimum: beforeChange + 5, timeout: 35, metric: "tv_track_change"))
            capture("Subtitle selection renegotiated to HLS")
            #else
            selectTouchTrack(type: "audio", index: audio)
            selectTouchTrack(type: "subtitle", index: subtitle)
            openTouchTrackPanel()
            let subtitleChoice = app.buttons["subtitle-\(subtitle)"]
            XCTAssertEqual(subtitleChoice.value as? String, "Selected")
            capture("Explicit audio and subtitle choices")
            activate(app.buttons["playerOptionsDone"])
            expectation(for: NSPredicate(format: "label == %@", "Server HLS"), evaluatedWith: method)
            waitForExpectations(timeout: 30)
            let beforeChange = seconds(position.label)
            XCTAssertTrue(waitForPlaybackClock(position, minimum: beforeChange + 5, timeout: 30, metric: "touch_track_change"))
            capture("Subtitle selection renegotiated to HLS")
            if let cue = config["directSubtitleCueSeconds"].flatMap(Int.init) {
                XCTAssertTrue(waitForPlaybackClock(position, minimum: cue, timeout: TimeInterval(cue + 30), metric: "touch_positive_subtitle_cue"))
                XCTAssertGreaterThanOrEqual(seconds(position.label), cue)
                capture("Touch selected subtitle playback after track apply")
            }
            #endif
        }
        #if os(tvOS)
        leaveNativeTVPlayer(returningTo: play)
        #else
        activate(app.buttons["closePlayer"])
        #endif
        XCTAssertTrue(play.waitForExistence(timeout: 15))
        back()
        for _ in containers { back() }
    }
    private func openSampleDetail(id: String, key: String) throws -> XCUIElement {
        let button = app.buttons["item-\(id)"]
        if !sampleFolderOpen {
            #if os(iOS)
            // Each test launch can restore a different root tab from NavigationMemory. Start
            // catalog traversal from Home before opening the configured library hierarchy.
            selectHomeRoot()
            #endif
            #if os(tvOS)
            capture("Native TV Home before \(key) catalog navigation")
            #endif
            for key in ["libraryID", "samplesFolderID"] {
                if let containerID = config[key] {
                    let container = app.buttons["item-\(containerID)"]
                    reveal(container)
                    XCTAssertTrue(container.waitForExistence(timeout: 5), "Missing configured \(key)")
                    activate(container)
                }
            }
            sampleFolderOpen = true
        }
        let containerKey = key + "Containers"
        let containers = (config[containerKey] ?? "").split(separator: "|").map(String.init)
        for containerID in containers {
            let folder = app.buttons["item-\(containerID)"]
            reveal(folder); activate(folder)
        }
        capture("After configured \(key) folder")
        reveal(button)
        XCTAssertTrue(button.waitForExistence(timeout: 10))
        activate(button)
        XCTAssertTrue(app.staticTexts["detailTitle"].waitForExistence(timeout: 15))
        if let name = config[key + "Name"] { XCTAssertEqual(app.staticTexts["detailTitle"].label, name) }
        capture("\(key.capitalized) media detail")
        return button
    }
    private func openSeriesEpisodeDetail() throws {
        if let libraryID = config["showsLibraryID"] {
            let library = app.buttons["item-\(libraryID)"].firstMatch
            reveal(library); activate(library)
            capture("TV shows library")
        }
        let seriesID = try XCTUnwrap(config["seriesID"])
        let series = app.buttons["item-\(seriesID)"].firstMatch
        reveal(series); activate(series)
        let seasonID = try XCTUnwrap(config["seasonID"])
        let season = app.buttons["season-\(seasonID)"]
        reveal(season); activate(season)
        let episodeID = try XCTUnwrap(config["episodeID"])
        let episode = app.buttons["item-\(episodeID)"]
        reveal(episode); activate(episode)
        XCTAssertTrue(app.staticTexts["detailTitle"].waitForExistence(timeout: 15))
    }
    private func parsedClockSeconds(_ label: String) -> Int? {
        let parts = label.split(separator: ":", omittingEmptySubsequences: false)
        guard parts.count == 2 || parts.count == 3 else { return nil }
        let values = parts.compactMap { Int($0) }
        guard values.count == parts.count, values.allSatisfy({ $0 >= 0 }), values.last! < 60 else { return nil }
        if values.count == 2 { return values[0] * 60 + values[1] }
        guard values[1] < 60 else { return nil }
        return values[0] * 3600 + values[1] * 60 + values[2]
    }
    private func seconds(_ label: String) -> Int {
        parsedClockSeconds(label) ?? 0
    }
    private func submitAndDismissSearchKeyboard(field: XCUIElement, result: XCUIElement) {
        let keyboard = app.keyboards.firstMatch
        if keyboard.waitForExistence(timeout: 3) {
            let submit = keyboard.buttons.matching(NSPredicate(format: "label ==[c] %@", "search")).firstMatch
            if submit.waitForExistence(timeout: 2) { activate(submit) }
            else { field.typeText("\n") }
            #if os(tvOS)
            if keyboard.exists { XCUIRemote.shared.press(.menu) }
            #endif
        } else {
            field.typeText("\n")
        }
        XCTAssertTrue(keyboard.waitForNonExistence(timeout: 10), "Submitting search or closing the native keyboard should leave the results screen visible")
        XCTAssertTrue(field.exists, "Search should remain open after the native keyboard is dismissed")
        XCTAssertTrue(result.isHittable, "The selected result should be reachable after the keyboard is dismissed")
    }
    private func ensureSignedIn() {
        if hasHomeRoot(timeout: 2) { returnHome(); return }
        // NavigationMemory intentionally restores the last authenticated stack. A detail,
        // library, settings, search, account, or player route is signed in even when Home's
        // toolbar identifiers are not currently in the accessibility tree.
        if hasAuthenticatedNavigationState() { returnHome(); return }

        // Clones may preserve this app's remembered server/account list. Select the pilot
        // account before deciding whether to sign in; never emit a full AX tree because it can
        // contain entered form data.
        let rememberedAccount = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "account-")).firstMatch
        if rememberedAccount.waitForExistence(timeout: 2) {
            activate(rememberedAccount)
            if hasHomeRoot(timeout: 15) { returnHome(); return }
        } else if app.buttons["addServer"].waitForExistence(timeout: 1) {
            activate(app.buttons["addServer"])
        }

        let quickCancel = app.buttons["cancelQuick"]
        if quickCancel.waitForExistence(timeout: 1) { activate(quickCancel) }
        let signIn = app.buttons["signIn"]
        if !signIn.waitForExistence(timeout: 3) {
            let connect = app.buttons["connect"]
            if connect.exists {
                capture("Server address sign-in")
                activate(connect)
            }
        }
        guard signIn.waitForExistence(timeout: 25) else {
            let known = ["connect", "signIn", "serverAddress", "accountsTitle", "addServer", "cancelQuick", "quickCode", "signOut", "openSearch", "openSettings"]
            let visible = app.descendants(matching: .any).allElementsBoundByIndex
                .map(\.identifier).filter { known.contains($0) || $0.hasPrefix("account-") }
            XCTFail("Could not reach password sign-in. Visible safe identifiers: \(visible.joined(separator: ","))")
            return
        }
        dismissKeyboardAndTypingIntro()
        capture("Password sign-in")
        activate(signIn)
        XCTAssertTrue(hasHomeRoot(timeout: 30), "Successful sign-in should expose Home's Search or Settings toolbar action")
        returnHome()
    }
    private func selectHomeRoot() {
        let home = app.buttons["Home"]
        if home.waitForExistence(timeout: 5) { activate(home) }
        if !hasHomeRoot(timeout: 1) { returnHome() }
        XCTAssertTrue(hasHomeRoot(timeout: 5), "The Home tab should be selected before using a deep-link detail hook")
    }
    private func dismissKeyboardAndTypingIntro() {
        #if os(iOS)
        let introButton = app.buttons.matching(NSPredicate(format: "label CONTAINS[c] %@ OR label CONTAINS[c] %@", "Continue", "Get Started")).firstMatch
        if introButton.waitForExistence(timeout: 1), introButton.isHittable {
            print("F1_AX keyboard_intro_button=\(introButton.label)")
            activate(introButton)
        } else {
            print("F1_AX keyboard_intro_button=not_exposed")
        }
        if app.keyboards.firstMatch.exists {
            let done = app.buttons["dismissSignInKeyboard"]
            if done.waitForExistence(timeout: 2), done.isHittable {
                activate(done)
            } else {
                // The form opts into interactive scroll dismissal; do not tap a field label or
                // background and assume focus has cleared.
                app.swipeDown()
            }
            XCTAssertTrue(app.keyboards.firstMatch.waitForNonExistence(timeout: 5), "The sign-in keyboard should be dismissed before the lower-actions capture")
        }
        print("F1_AX keyboard_after_dismiss=\(app.keyboards.firstMatch.exists)")
        #endif
    }
    private func waitForSettledMethod(_ method: XCUIElement, expected: String? = nil, timeout: TimeInterval) -> Bool {
        let deadline = ProcessInfo.processInfo.systemUptime + timeout
        while ProcessInfo.processInfo.systemUptime < deadline {
            if method.exists {
                let label = method.label
                if !label.isEmpty, label != "Preparing playback…", label != "Preparing playback..." {
                    return expected == nil || label == expected
                }
            }
            Thread.sleep(forTimeInterval: 0.25)
        }
        print("F1_METRIC playback_method_settle_timeout expected=\(expected ?? "Direct or Server HLS") actual=\(method.exists ? method.label : "<missing>")")
        return false
    }
    /// Wait for a paused AVKit clock to return near its saved position after an item replacement.
    /// Unlike waitForPlaybackClock, this does not require movement or synthesize a playback input.
    private func waitForPausedClockNear(_ position: XCUIElement, target: Int, tolerance: Int, timeout: TimeInterval, metric: String) -> Bool {
        let startedAt = ProcessInfo.processInfo.systemUptime
        let deadline = startedAt + timeout
        var lastLabel = "<missing>"
        while ProcessInfo.processInfo.systemUptime < deadline {
            lastLabel = position.exists ? position.label : "<missing>"
            if let current = parsedClockSeconds(lastLabel), abs(current - target) <= tolerance {
                print("F1_METRIC \(metric)_restored_ms=\(Int((ProcessInfo.processInfo.systemUptime - startedAt) * 1000)) target=\(target) observed=\(current)")
                return true
            }
            Thread.sleep(forTimeInterval: 0.25)
        }
        print("F1_METRIC \(metric)_restore_timeout_ms=\(Int((ProcessInfo.processInfo.systemUptime - startedAt) * 1000)) target=\(target) final_position=\(lastLabel)")
        return false
    }
    /// Track replacement can leave the old AVKit clock visible while the new item is still waiting.
    /// Require the app-owned waiting overlay to be gone before treating that clock as restored.
    private func waitForPlaybackWaitingToClear(timeout: TimeInterval, metric: String) -> Bool {
        let waiting = app.descendants(matching: .any).matching(identifier: "playbackWaiting").firstMatch
        let startedAt = ProcessInfo.processInfo.systemUptime
        let deadline = startedAt + timeout
        var observed = false
        var clearSamples = 0
        while ProcessInfo.processInfo.systemUptime < deadline {
            if waiting.exists {
                observed = true
                clearSamples = 0
            } else {
                clearSamples += 1
                if clearSamples >= 2 {
                    print("F1_METRIC \(metric)_waiting_clear_ms=\(Int((ProcessInfo.processInfo.systemUptime - startedAt) * 1000)) observed=\(observed)")
                    return true
                }
            }
            Thread.sleep(forTimeInterval: 0.25)
        }
        print("F1_METRIC \(metric)_waiting_timeout_ms=\(Int((ProcessInfo.processInfo.systemUptime - startedAt) * 1000)) observed=\(observed) stillVisible=\(waiting.exists)")
        return false
    }
    private func hasHomeRoot(timeout: TimeInterval = 1) -> Bool {
        app.buttons["openSearch"].waitForExistence(timeout: timeout)
            || app.buttons["openSettings"].waitForExistence(timeout: timeout)
    }
    private func hasAuthenticatedNavigationState() -> Bool {
        let fixedMarkers: [XCUIElement] = [
            app.staticTexts["detailTitle"], app.staticTexts["settingsTitle"], app.staticTexts["accountsTitle"],
            app.staticTexts["aboutTitle"], app.textFields["searchField"], app.buttons["play"],
            app.staticTexts["playbackMethod"], app.staticTexts["playbackPosition"], app.buttons["Pictureshow"],
            app.buttons["closePlayer"], app.buttons["playerTracks"], app.buttons["playerOptions"]
        ]
        if fixedMarkers.contains(where: \.exists) { return true }
        let rootLabels = ["Home", "Libraries", "Search", "Settings"]
        if rootLabels.contains(where: { app.buttons.matching(NSPredicate(format: "label == %@", $0)).firstMatch.exists }) { return true }
        return app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "item-")).firstMatch.exists
    }
    /// Reveal controls only while the clock itself is missing or empty. Tapping the center of an
    /// already-present AVKit player can pause playback, so a clock below the target is never a
    /// reason to synthesize a reveal tap.
    private func waitForPlaybackClock(_ position: XCUIElement, minimum: Int, timeout: TimeInterval, metric: String, nativeAVKitClock: Bool = false) -> Bool {
        let startedAt = ProcessInfo.processInfo.systemUptime
        let deadline = startedAt + timeout
        var lastRevealAt = startedAt - 3
        var lastObservedSeconds: Int?
        var lastAdvanceAt = startedAt
        var nativeRefreshRequested = false
        var nativeClockAdvancedAfterRefresh = false
        while ProcessInfo.processInfo.systemUptime < deadline {
            let now = ProcessInfo.processInfo.systemUptime
            let exists = position.exists
            let label = exists ? position.label : ""
            let currentSeconds = label.isEmpty ? nil : seconds(label)
            let isHittable = exists && position.isHittable
            if let currentSeconds, let previousSeconds = lastObservedSeconds, currentSeconds != previousSeconds {
                if nativeAVKitClock && nativeRefreshRequested { nativeClockAdvancedAfterRefresh = true }
                lastObservedSeconds = currentSeconds
                lastAdvanceAt = now
            } else if lastObservedSeconds == nil {
                lastObservedSeconds = currentSeconds
            }
            if let currentSeconds, currentSeconds >= minimum, (!nativeAVKitClock || nativeClockAdvancedAfterRefresh) {
                print("F1_METRIC \(metric)_clock_advanced_ms=\(Int((now - startedAt) * 1000)) seconds=\(currentSeconds) hittable=\(isHittable)")
                return true
            }
            let staleNativeClock = nativeAVKitClock && now - lastAdvanceAt >= 2
            let needsReveal = !exists || label.isEmpty || (nativeAVKitClock && !isHittable) || staleNativeClock
            if needsReveal, now - lastRevealAt >= 3 {
                #if os(tvOS)
                XCUIRemote.shared.press(.up)
                if nativeAVKitClock { nativeRefreshRequested = true }
                #else
                let options = app.buttons["playerOptions"]
                if options.exists { activate(options) }
                else { app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap() }
                #endif
                lastRevealAt = now
                if nativeAVKitClock { print("F1_METRIC \(metric)_clock_reveal_up stale=\(staleNativeClock) hittable=\(isHittable) label=\(label)") }
            }
            Thread.sleep(forTimeInterval: 0.35)
        }
        let finalLabel = position.exists ? position.label : "<missing>"
        print("F1_METRIC \(metric)_clock_timeout_ms=\(Int((ProcessInfo.processInfo.systemUptime - startedAt) * 1000)) final_position=\(finalLabel) hittable=\(position.exists && position.isHittable)")
        return false
    }
    #if os(iOS)
    private func assertTouchPlayerActionLabels() {
        let expected: [(String, String)] = [
            ("closePlayer", "Back to details"),
            ("playerTracks", "Audio and subtitles"),
            ("playerSeek", "Seek"),
            ("playerChapters", "Chapters"),
            ("rotatePlayer", "Rotate portrait or landscape")
        ]
        let observed = expected.map { identifier, label -> String in
            let button = app.buttons[identifier]
            XCTAssertTrue(button.waitForExistence(timeout: 10), "Missing touch player action \(identifier)")
            XCTAssertEqual(button.label, label)
            return "\(identifier)=\(button.label)"
        }
        print("F1_AX touch_player_actions " + observed.joined(separator: " | "))
    }
    /// iOS track actions commit immediately and dismiss the sheet. Reopen it for each choice;
    /// this also avoids relying on a transient toolbar item after AVKit hides its controls.
    private func openTouchTrackPanel() {
        let tracks = app.buttons["playerTracks"]
        if !tracks.waitForExistence(timeout: 20) {
            capture("Player action strip missing")
            let closeVisible = app.buttons["closePlayer"].exists
            let methodVisible = app.staticTexts["playbackMethod"].exists
            let positionVisible = app.staticTexts["playbackPosition"].exists
            let optionsVisible = app.buttons["playerOptions"].exists
            print("F1_AX missing_player_tracks=true close=\(closeVisible) method=\(methodVisible) position=\(positionVisible) options=\(optionsVisible)")
            XCTFail("The always-visible iOS action strip should expose playerTracks after AVKit presentation settles")
            return
        }
        reveal(tracks); activate(tracks)
        XCTAssertTrue(app.buttons["playerOptionsDone"].waitForExistence(timeout: 10))
    }
    private func selectTouchTrack(type: String, index: String) {
        openTouchTrackPanel()
        let choice = app.buttons["\(type)-\(index)"]
        reveal(choice)
        waitForEnabled(choice, description: "\(type) track \(index)")
        activate(choice)
        if type == "audio" {
            XCTAssertTrue(app.staticTexts["Audio and subtitles"].waitForNonExistence(timeout: 10), "Selecting audio should immediately dismiss the track sheet")
        } else {
            XCTAssertTrue(app.staticTexts["Audio and subtitles"].waitForNonExistence(timeout: 10), "Selecting subtitles should immediately dismiss the track sheet")
        }
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "\(type)-")).firstMatch.waitForNonExistence(timeout: 10), "Selected \(type) rows should leave the sheet when the choice commits")
        openTouchTrackPanel()
        let settledChoice = app.buttons["\(type)-\(index)"]
        reveal(settledChoice)
        waitForEnabled(settledChoice, description: "settled \(type) track \(index)")
        XCTAssertEqual(settledChoice.value as? String, "Selected", "The requested \(type) track should be active after replacement settles")
        activate(app.buttons["playerOptionsDone"])
    }
    private func waitForEnabled(_ element: XCUIElement, description: String) {
        let deadline = ProcessInfo.processInfo.systemUptime + 30
        while ProcessInfo.processInfo.systemUptime < deadline {
            if element.exists, element.isEnabled { return }
            Thread.sleep(forTimeInterval: 0.25)
        }
        XCTAssertTrue(element.exists && element.isEnabled, "Timed out waiting for \(description) to settle and enable")
    }
    #endif
    #if os(tvOS)
    /// All fields in these observations come from one immutable accessibility tree.
    /// Looking up attributes on live XCUIElements separately can cross a HUD transition.
    private struct TVPlayerSnapshot {
        let bounds: CGRect
        let nodes: [XCUIElementSnapshot]
        func first(identifier: String) -> XCUIElementSnapshot? {
            nodes.first { $0.identifier == identifier }
        }
    }
    private struct TVInfoObservation {
        let method: String
        let clock: String
        let backFrame: CGRect
        let forwardFrame: CGRect
    }
    private struct TVClockObservation {
        let seconds: Int
        let method: String?
    }
    private func tvSnapshotNodes(_ root: XCUIElementSnapshot) -> [XCUIElementSnapshot] {
        [root] + root.children.flatMap { tvSnapshotNodes($0) }
    }
    private func tvNativeInfoCells(_ collection: XCUIElementSnapshot, within bounds: CGRect) -> [XCUIElementSnapshot] {
        tvSnapshotNodes(collection).filter { node in
            guard node.elementType == .cell, node.frame.width > 0, node.frame.height > 0,
                  bounds.contains(node.frame) else { return false }
            // AVKit's first tab overhangs its collection by 4 points in the
            // observed native layout. Preserve subtree membership and require
            // at least 90% overlap, rather than dropping that real native cell.
            let overlap = collection.frame.intersection(node.frame)
            return !overlap.isNull && overlap.width * overlap.height >= node.frame.width * node.frame.height * 0.9
        }
    }
    private func tvPlayerSnapshot(allowNativeTabNavigation: Bool = false) -> TVPlayerSnapshot? {
        guard let root = try? app.snapshot() else { return nil }
        let nodes = tvSnapshotNodes(root)
        let playerFrame: CGRect
        if let player = nodes.first(where: { $0.identifier == "pictureshowNativePlayer" }),
           player.frame.width > 0, player.frame.height > 0 {
            playerFrame = player.frame
        } else {
            // AVKit's custom-info presentation temporarily omits the containing
            // player view from AX. The default acceptance path requires its own
            // native tab surface AND every app-owned Info control in one tree;
            // the app root by itself is never a player marker.
            // During tab navigation AVKit also omits the containing player while
            // its built-in Info tab is selected. Keep that native collection
            // observable so a real remote move can reach the custom tab. This
            // permits navigation only; tvInfoObservation still requires every
            // app-owned method/clock/seek field before accepting Info.
            let required = allowNativeTabNavigation
                ? ["AVInfoMenuCollection"]
                : ["AVInfoMenuCollection", "playbackMethod", "playbackPosition", "seekBackward", "seekForward"]
            guard required.allSatisfy({ identifier in
                let matches = nodes.filter { $0.identifier == identifier }
                return matches.count == 1 && matches[0].frame.width > 0 && matches[0].frame.height > 0
                    && root.frame.contains(matches[0].frame)
            }) else { return nil }
            if allowNativeTabNavigation {
                let collection = nodes.first { $0.identifier == "AVInfoMenuCollection" }!
                let cells = tvNativeInfoCells(collection, within: root.frame)
                guard cells.count >= 2 else { return nil }
            }
            playerFrame = root.frame
        }
        return TVPlayerSnapshot(bounds: playerFrame, nodes: nodes.filter {
            $0.frame.width > 0 && $0.frame.height > 0 && playerFrame.contains($0.frame)
                && root.frame.contains($0.frame)
        })
    }
    private func tvInfoObservation(_ snapshot: TVPlayerSnapshot) -> TVInfoObservation? {
        guard let method = snapshot.first(identifier: "playbackMethod"), !method.label.isEmpty,
              let clock = snapshot.first(identifier: "playbackPosition"), parsedClockSeconds(clock.label) != nil,
              let back = snapshot.first(identifier: "seekBackward"),
              let forward = snapshot.first(identifier: "seekForward") else { return nil }
        return TVInfoObservation(method: method.label, clock: clock.label,
                                 backFrame: back.frame, forwardFrame: forward.frame)
    }
    /// Up reveals AVKit's elapsed-time HUD without pausing or seeking. The custom
    /// Info tab already exposes its own clock; Up would leave that selected panel.
    /// Every observation still comes from the current immutable native tree.
    private func waitForTVPlayerClock(identifier: String, minimum: Int, maximum: Int? = nil,
                                      timeout: TimeInterval, metric: String, expectedMethod: String? = nil,
                                      requiredTitle: String? = nil, revealNativeHUD: Bool = true) -> TVClockObservation? {
        let started = ProcessInfo.processInfo.systemUptime
        let deadline = started + timeout
        var lastReveal = started - 2
        var lastClock = "<not exposed>"
        while ProcessInfo.processInfo.systemUptime < deadline {
            let now = ProcessInfo.processInfo.systemUptime
            if revealNativeHUD, identifier == "AXElapsedTime", now - lastReveal >= 2 {
                XCUIRemote.shared.press(.up)
                lastReveal = ProcessInfo.processInfo.systemUptime
            }
            if let snapshot = tvPlayerSnapshot(), let clock = snapshot.first(identifier: identifier) {
                lastClock = clock.label
                let method = snapshot.first(identifier: "playbackMethod")?.label
                let hasExpectedInfo = expectedMethod == nil || (tvInfoObservation(snapshot)?.method == expectedMethod)
                let hasTitle = requiredTitle.map { title in snapshot.nodes.contains { $0.label == title } } ?? true
                if let value = parsedClockSeconds(clock.label), value >= minimum,
                   maximum.map({ value <= $0 }) ?? true, hasExpectedInfo, hasTitle,
                   snapshot.first(identifier: "playbackWaiting") == nil {
                    print("F1_METRIC \(metric)_clock_advanced_ms=\(Int((ProcessInfo.processInfo.systemUptime - started) * 1000)) seconds=\(value) method=\(method ?? "<not exposed>") snapshot=true")
                    return TVClockObservation(seconds: value, method: method)
                }
            }
            Thread.sleep(forTimeInterval: 0.35)
        }
        print("F1_METRIC \(metric)_clock_timeout_ms=\(Int((ProcessInfo.processInfo.systemUptime - started) * 1000)) final_position=\(lastClock) snapshot=true")
        return nil
    }
    /// Capture one bounded, same-tree diagnostic before XCTest tears the app down.
    /// A post-run simulator screenshot can show Home and cannot diagnose this panel.
    private func diagnoseTVInfo(step: String) {
        guard let root = try? app.snapshot() else {
            print("F2_TV_INFO_DIAGNOSTIC step=\(step) snapshot=failed")
            return
        }
        let identifiers: Set<String> = ["pictureshowNativePlayer", "AVInfoMenuCollection",
                                        "playbackMethod", "playbackPosition", "seekBackward", "seekForward"]
        let nodes = tvSnapshotNodes(root).filter { identifiers.contains($0.identifier) }
        let playerFrame = nodes.first { $0.identifier == "pictureshowNativePlayer" }?.frame
        print("F2_TV_INFO_DIAGNOSTIC step=\(step) appState=\(app.state) rootFrame=\(root.frame) matchedNodes=\(nodes.count)")
        for node in nodes.prefix(16) {
            let insidePlayer = playerFrame.map { $0.contains(node.frame) } ?? false
            print("F2_TV_INFO_NODE step=\(step) id=\(node.identifier) label=\(node.label) frame=\(node.frame) insidePlayer=\(insidePlayer) insideRoot=\(root.frame.contains(node.frame)) enabled=\(node.isEnabled)")
        }
        captureFullScreen("Native TV Info \(step) before teardown")
    }
    /// Navigate AVKit's final custom Info tab from one immutable tree per input.
    /// Repeated live element queries can outlast the HUD before the first key.
    @discardableResult
    private func openNativeTVInfoTab() -> TVInfoObservation? {
        func finish(_ observation: TVInfoObservation, step: String) -> TVInfoObservation {
            print("F1_AX tv_info_content_visible step=\(step) method=\(observation.method) position=\(observation.clock) seekFrames=\(observation.backFrame),\(observation.forwardFrame) snapshot=true")
            captureFullScreen("Native TV Info \(step)")
            return observation
        }
        let initialDeadline = ProcessInfo.processInfo.systemUptime + 5
        var initial = tvPlayerSnapshot()
        while initial == nil && ProcessInfo.processInfo.systemUptime < initialDeadline {
            Thread.sleep(forTimeInterval: 0.15)
            initial = tvPlayerSnapshot()
        }
        guard let initial else {
            XCTFail("The identified native player must exist before opening Info")
            return nil
        }
        if let observation = tvInfoObservation(initial) { return finish(observation, step: "already-visible") }
        XCUIRemote.shared.press(.up)
        var lastNavigationAt = ProcessInfo.processInfo.systemUptime
        let deadline = ProcessInfo.processInfo.systemUptime + 15
        var refreshes = 0
        var noFocus = 0
        var transitionDowns = 0
        var focusedAt: TimeInterval?
        var selected = false
        var step = 0
        while ProcessInfo.processInfo.systemUptime < deadline {
            guard let snapshot = tvPlayerSnapshot(allowNativeTabNavigation: true) else {
                Thread.sleep(forTimeInterval: 0.15)
                continue
            }
            if tvInfoObservation(snapshot) != nil,
               let accepted = tvPlayerSnapshot().flatMap(tvInfoObservation) {
                return finish(accepted, step: "after-navigation")
            }
            // Selecting is needed on some native states; after that input, wait
            // passively for content rather than closing it with more arrows.
            if selected { Thread.sleep(forTimeInterval: 0.15); continue }
            // Native tab expansion takes longer than a remote key dispatch.
            // The observed successful path spaced its Down inputs by >1 s;
            // dispatching the next key during that transition hid the surface.
            guard ProcessInfo.processInfo.systemUptime - lastNavigationAt >= 1 else {
                Thread.sleep(forTimeInterval: 0.15)
                continue
            }
            guard let collection = snapshot.first(identifier: "AVInfoMenuCollection") else {
                guard refreshes < 3 else { break }
                refreshes += 1; XCUIRemote.shared.press(.up)
                lastNavigationAt = ProcessInfo.processInfo.systemUptime
                continue
            }
            guard snapshot.nodes.filter({ $0.identifier == "AVInfoMenuCollection" }).count == 1 else { break }
            let cells = tvNativeInfoCells(collection, within: snapshot.bounds)
            // AVKit may omit the custom tab's AX label. The sole configured
            // custom controller is appended after its built-in Info/Chapters tabs.
            guard cells.count >= 2, let target = cells.last, target.isEnabled,
                  collection.frame.contains(target.frame) else { break }
            let focusedCell = cells.first { $0.hasFocus }
            let playerFocus = snapshot.first(identifier: "pictureshowNativePlayer") == nil ? [] : snapshot.nodes.filter {
                $0.hasFocus && ($0.elementType == .cell || $0.elementType == .button)
                    && $0.frame.width > 0 && $0.frame.height > 0
                    && $0.frame.width < collection.frame.width
                    && $0.frame.height < collection.frame.height * 2
            }
            let focused = focusedCell ?? playerFocus.min {
                $0.frame.width * $0.frame.height < $1.frame.width * $1.frame.height
            }
            print("F2_TV_INFO_NAV step=\(step) target=\(target.frame) focused=\(focused.map { String(describing: $0.frame) } ?? "<unexposed>") refreshes=\(refreshes)")
            step += 1
            if target.hasFocus || focused?.frame == target.frame {
                noFocus = 0
                if let focusedAt, ProcessInfo.processInfo.systemUptime - focusedAt >= 1 {
                    XCUIRemote.shared.press(.select); selected = true
                    lastNavigationAt = ProcessInfo.processInfo.systemUptime
                } else if focusedAt == nil { focusedAt = ProcessInfo.processInfo.systemUptime }
                Thread.sleep(forTimeInterval: 0.15)
            } else if let focused {
                focusedAt = nil; noFocus = 0
                let dx = target.frame.midX - focused.frame.midX
                let dy = target.frame.midY - focused.frame.midY
                XCUIRemote.shared.press(abs(dy) > 30 ? (dy > 0 ? .down : .up) : (dx > 0 ? .right : .left))
                lastNavigationAt = ProcessInfo.processInfo.systemUptime
            } else {
                focusedAt = nil; noFocus += 1
                // The observed collapsed native collection has three cells and
                // temporarily exposes only root focus. Its successful expansion
                // needs another Down after animation, not root-frame geometry.
                if noFocus == 1 {
                    let state = snapshot.nodes.filter {
                        $0.identifier == "AVInfoMenuCollection" || $0.elementType == .cell || $0.hasFocus
                    }
                    for node in state.prefix(16) {
                        print("F2_TV_INFO_TRANSITION type=\(node.elementType.rawValue) id=\(node.identifier) label=\(node.label) frame=\(node.frame) focus=\(node.hasFocus)")
                    }
                }
                guard cells.count == 3, transitionDowns < 3 else { break }
                transitionDowns += 1
                print("F2_TV_INFO_TRANSITION_DOWN attempt=\(transitionDowns) collection=\(collection.frame) cells=\(cells.count)")
                XCUIRemote.shared.press(.down)
                lastNavigationAt = ProcessInfo.processInfo.systemUptime
            }
        }
        diagnoseTVInfo(step: "missing required Info fields")
        XCTFail("Remote navigation must expose method, actual clock and both seek controls together in the native player")
        return nil
    }
    private func diagnoseTVManualNext(step: String) {
        captureFullScreen("Native TV manual Next \(step) before teardown")
        guard let root = try? app.snapshot() else {
            print("F2_TV_NEXT_DIAGNOSTIC step=\(step) snapshot=failed")
            return
        }
        let identifiers: Set<String> = ["pictureshowNativePlayer", "nextEpisodeMenu", "playNextTransport",
                                        "cancelNextTransport", "AXElapsedTime", "play"]
        let titles = [config["episodeName"], config["nextEpisodeName"]].compactMap { $0 }
        let nodes = tvSnapshotNodes(root).filter {
            identifiers.contains($0.identifier) || titles.contains($0.label)
                || $0.label == "Next episode" || $0.label == "Cancel next episode" || $0.label.hasPrefix("Play next: ")
        }
        print("F2_TV_NEXT_DIAGNOSTIC step=\(step) appState=\(app.state) matchedNodes=\(nodes.count)")
        for node in nodes.prefix(16) {
            print("F2_TV_NEXT_NODE step=\(step) id=\(node.identifier) label=\(node.label) frame=\(node.frame) enabled=\(node.isEnabled)")
        }
    }
    private func nativeTVTransportMenu(_ title: String) -> XCUIElement {
        if let snapshot = tvPlayerSnapshot(), tvInfoObservation(snapshot) != nil {
            // A selected custom Info page owns the remote until Back closes it.
            // Up navigates that page; it does not reveal the transport underneath.
            XCUIRemote.shared.press(.menu)
            let deadline = ProcessInfo.processInfo.systemUptime + 5
            var closed = false
            while ProcessInfo.processInfo.systemUptime < deadline {
                if let current = tvPlayerSnapshot(), tvInfoObservation(current) == nil {
                    closed = true
                    break
                }
                Thread.sleep(forTimeInterval: 0.15)
            }
            guard closed else {
                captureFullScreen("Native TV Info must close before \(title) transport menu")
                XCTFail("Back must close the custom Info page while retaining the native player before track navigation")
                return app.cells.matching(NSPredicate(format: "label == %@ AND identifier != %@ AND identifier != %@", title, "AVLegibleSettings", "AVAudibleSettings")).firstMatch
            }
        }
        XCUIRemote.shared.press(.up)
        // AVKit can retain its built-in audible/legible menus alongside the app's server
        // menu. Only the custom menu contains the negotiated Stream N actions.
        let query = app.cells.matching(NSPredicate(format: "label == %@ AND identifier != %@ AND identifier != %@", title, "AVLegibleSettings", "AVAudibleSettings"))
        let matching = query.firstMatch
        // AVKit moves this row upward while Info is expanded. Bind the exact
        // native cell to its containing transport collection, not a screen band.
        func candidates() -> [(row: XCUIElementSnapshot, cell: XCUIElementSnapshot)] {
            let rows = tvPlayerSnapshot()?.nodes.filter {
                $0.elementType == .collectionView && $0.frame.width > app.frame.width * 0.90 && $0.frame.height < 110
            } ?? []
            return rows.flatMap { row in
                tvSnapshotNodes(row).filter {
                    $0.elementType == .cell && $0.label == title && $0.identifier != "AVLegibleSettings" && $0.identifier != "AVAudibleSettings"
                        && $0.frame.width > 0 && $0.frame.height > 0
                        && row.frame.contains($0.frame)
                }.map { (row: row, cell: $0) }
            }
        }
        let deadline = ProcessInfo.processInfo.systemUptime + 5
        var settled = candidates()
        while settled.count != 1 && ProcessInfo.processInfo.systemUptime < deadline {
            Thread.sleep(forTimeInterval: 0.15)
            settled = candidates()
        }
        guard settled.count == 1, let candidate = settled.first else {
            if let snapshot = tvPlayerSnapshot() {
                let rows = snapshot.nodes.filter { $0.elementType == .collectionView }
                print("F2_TV_TRANSPORT_DIAGNOSTIC title=\(title) candidates=\(settled.count) rows=\(rows.count)")
                for row in rows.prefix(4) {
                    for cell in tvSnapshotNodes(row).filter({ $0.elementType == .cell }).prefix(12) {
                        print("F2_TV_TRANSPORT_CELL row=\(row.frame) id=\(cell.identifier) label=\(cell.label) frame=\(cell.frame) focused=\(cell.hasFocus)")
                    }
                }
            }
            captureFullScreen("Native TV missing \(title) transport row before teardown")
            XCTFail("The canonical native player must expose one transport row containing the exact \(title) cell")
            return matching
        }
        guard matching.waitForExistence(timeout: 5), query.count == 1,
              candidate.row.frame.contains(matching.frame) else {
            captureFullScreen("Native TV missing \(title) transport control before teardown")
            XCTFail("AVKit should expose the exact native \(title) transport control")
            return matching
        }
        print("F1_AX tv_transport_candidates title=\(title) row=\(candidate.row.frame) matched=\(matching.frame) label=\(matching.label) snapshot=true")
        return matching
    }
    private func waitForNativeTVMenuEnabled(_ element: XCUIElement, description: String) {
        let deadline = ProcessInfo.processInfo.systemUptime + 30
        while ProcessInfo.processInfo.systemUptime < deadline {
            if element.exists, element.isEnabled { return }
            Thread.sleep(forTimeInterval: 0.25)
        }
        XCTAssertTrue(element.exists && element.isEnabled, "Timed out waiting for \(description) to settle and enable")
    }
    private func nativeTVTrackChoice(menuTitle: String, index: String) -> XCUIElement {
        let trigger = nativeTVTransportMenu(menuTitle)
        XCTAssertTrue(trigger.waitForExistence(timeout: 10), "Missing native AVKit \(menuTitle) menu trigger")
        XCTAssertEqual(trigger.label, menuTitle)
        activateNativeTVMenuChoice(trigger)

        let prefix = menuTitle == "Audio" ? "audio-" : "subtitle-"
        let query = app.descendants(matching: .any)
        let byIdentifier = query.matching(identifier: prefix + index).firstMatch
        let fallback: XCUIElement = index == "-1"
            ? query.matching(NSPredicate(format: "label MATCHES[c] %@", "^(selected,\\s*)?Off$")).firstMatch
            : query.matching(NSPredicate(format: "label MATCHES[c] %@", "^(selected,\\s*)?Stream \(index)(\\s·|$).*" )).firstMatch
        let choice = byIdentifier.waitForExistence(timeout: 2) ? byIdentifier : fallback
        XCTAssertTrue(choice.waitForExistence(timeout: 10), "The native \(menuTitle) menu should expose configured index \(index)")
        if index != "-1" {
            XCTAssertTrue(nativeTVTrackLabel(choice.label, matchesStream: index),
                          "The native \(menuTitle) choice must begin with the exact configured Stream \(index)")
        }
        print("F1_AX tv_track_menu_choice type=\(menuTitle) index=\(index) label=\(choice.label) value=\(choice.value ?? "")")
        waitForNativeTVMenuEnabled(choice, description: "native \(menuTitle) track \(index)")
        return choice
    }
    private func selectNativeTVMenuTrack(menuTitle: String, index: String) -> String {
        let choice = nativeTVTrackChoice(menuTitle: menuTitle, index: index)
        let label = choice.label
        activateNativeTVMenuChoice(choice)
        print("F1_AX tv_track_menu_action type=\(menuTitle) index=\(index) label=\(label)")
        return label
    }
    private func activateNativeTVMenuChoice(_ choice: XCUIElement, revealed: (() -> Bool)? = nil) {
        // AVKit rebuilds its native menu accessibility tree while the transport HUD changes.
        // Avoid app-wide allElementsBoundByIndex snapshots here: XCTest can invalidate an
        // indexed element between enumeration and its hasFocus read. Track actions are cells,
        // so query the live focused cell directly and navigate within that native surface.
        XCTAssertTrue(choice.waitForExistence(timeout: 10))
        XCTAssertTrue(choice.isEnabled, "The settled native AVKit menu choice should be enabled")
        func selectUnlessRevealed() {
            if let revealed {
                // Focusing an Info tab can reveal its content automatically.
                // Selecting that already-open tab would toggle it closed again.
                let deadline = ProcessInfo.processInfo.systemUptime + 1.5
                while ProcessInfo.processInfo.systemUptime < deadline {
                    if revealed() { return }
                    Thread.sleep(forTimeInterval: 0.15)
                }
            }
            XCUIRemote.shared.press(.select)
        }
        let focusedCellQuery = app.cells.matching(NSPredicate(format: "hasFocus == true"))
        for step in 0..<20 {
            if revealed?() == true { return }
            if choice.hasFocus { selectUnlessRevealed(); return }
            let focusedCell = focusedCellQuery.firstMatch
            let frame = focusedCell.exists ? focusedCell.frame : focusedFrame()
            print("F2_TV_NATIVE_FOCUS step=\(step) target=\(choice.frame) focused=\(frame.map { String(describing: $0) } ?? "<unexposed>")")
            if let frame,
               frame.contains(CGPoint(x: choice.frame.midX, y: choice.frame.midY)),
               frame.height < choice.frame.height * 3 {
                selectUnlessRevealed()
                return
            }
            let dx = frame.map { choice.frame.midX - $0.midX } ?? 0
            let dy = frame.map { choice.frame.midY - $0.midY } ?? 100
            let direction: XCUIRemote.Button = abs(dy) > 30 ? (dy > 0 ? .down : .up) : (dx > 0 ? .right : .left)
            XCUIRemote.shared.press(direction)
            Thread.sleep(forTimeInterval: 0.15)
            if revealed?() == true { return }
            if choice.hasFocus { selectUnlessRevealed(); return }
        }
        XCTFail("Native AVKit menu focus did not reach configured choice \(choice.label)")
    }
    /// Info changes its native AX tree while focus moves. Resolve the target and
    /// focused surface together, without keeping indexed XCUIElement handles.
    private func activateNativeTVInfoAction(identifier: String) {
        for step in 0..<20 {
            guard let snapshot = tvPlayerSnapshot(),
                  let target = snapshot.first(identifier: identifier), target.elementType == .button,
                  target.isEnabled, target.frame.width > 0, target.frame.height > 0 else {
                XCTFail("The current native Info snapshot must contain enabled \(identifier)")
                return
            }
            let focused = snapshot.nodes.filter { $0.hasFocus && $0.frame.width > 0 && $0.frame.height > 0 }
                .min { $0.frame.width * $0.frame.height < $1.frame.width * $1.frame.height }
            if target.hasFocus || focused.map({
                $0.frame.contains(CGPoint(x: target.frame.midX, y: target.frame.midY))
                    && $0.frame.height < target.frame.height * 3
            }) == true {
                XCUIRemote.shared.press(.select)
                return
            }
            guard let focused else {
                // AVKit can publish the rebuilt Info content one frame before
                // it restores a focus owner after a paused HLS seek.
                print("F2_TV_INFO_FOCUS step=\(step) target=\(identifier) focus=unexposed reacquire=down")
                XCUIRemote.shared.press(.down)
                Thread.sleep(forTimeInterval: 0.25)
                continue
            }
            let dx = target.frame.midX - focused.frame.midX
            let dy = target.frame.midY - focused.frame.midY
            let direction: XCUIRemote.Button = abs(dy) > 30 ? (dy > 0 ? .down : .up) : (dx > 0 ? .right : .left)
            print("F2_TV_INFO_FOCUS step=\(step) target=\(identifier) targetFrame=\(target.frame) focused=\(focused.frame)")
            XCUIRemote.shared.press(direction)
            Thread.sleep(forTimeInterval: 0.15)
        }
        XCTFail("Native Info focus did not reach \(identifier)")
    }
    private func inspectNativeTVMenuSelection(menuTitle: String, index: String) -> Bool {
        let choice = nativeTVTrackChoice(menuTitle: menuTitle, index: index)
        let value = choice.value as? String
        let isSelected = choice.isSelected || ["selected", "on", "1"].contains(value?.lowercased() ?? "")
            || choice.label.range(of: "^selected,\\s*", options: [.regularExpression, .caseInsensitive]) != nil
        print("F1_AX tv_track_menu_selected type=\(menuTitle) index=\(index) isSelected=\(choice.isSelected) value=\(value ?? "<unexposed>")")
        return isSelected
    }
    private func inspectNativeTVInfoAndTracks(preferDirect: Bool = false) {
        for menuTitle in ["Audio", "Subtitles"] {
            let trigger = nativeTVTransportMenu(menuTitle)
            XCTAssertTrue(trigger.waitForExistence(timeout: 10), "Missing native AVKit \(menuTitle) transport menu")
            XCTAssertEqual(trigger.label, menuTitle, "The native AVKit transport menu should expose its visible label")
            print("F1_AX tv_transport_menu_label=\(trigger.label)")
            activateNativeTVMenuChoice(trigger)
            capture("Native TV \(menuTitle) transport menu")
            let prefix = menuTitle == "Audio" ? "audio-" : "subtitle-"
            let directKey = menuTitle == "Audio" ? "directAudioIndex" : "directSubtitleIndex"
            let preferred = preferDirect ? config[directKey].map { prefix + $0 } : nil
            let actionQuery = app.descendants(matching: .any)
            let identified = preferred.map { actionQuery.matching(identifier: $0).firstMatch }
                ?? actionQuery.matching(NSPredicate(format: "identifier BEGINSWITH %@", prefix)).firstMatch
            var action = identified
            if !action.waitForExistence(timeout: 2) {
                // AVKit may expose native UIAction menu entries by their visible title rather
                // than the action identifier. NativePlayer includes “Stream N” in each title.
                if let streamIndex = preferDirect ? config[directKey] : nil {
                    action = actionQuery.matching(NSPredicate(format: "label MATCHES[c] %@", "^(selected,\\s*)?Stream \(streamIndex)(\\s·|$).*" )).firstMatch
                } else {
                    action = actionQuery.matching(NSPredicate(format: "label CONTAINS[c] %@", "Stream ")).firstMatch
                }
            }
            XCTAssertTrue(action.waitForExistence(timeout: 10), "Missing native AVKit \(menuTitle) menu items")
            if let streamIndex = preferDirect ? config[directKey] : nil {
                XCTAssertTrue(nativeTVTrackLabel(action.label, matchesStream: streamIndex),
                              "The native \(menuTitle) choice must match configured Stream \(streamIndex), not a similarly numbered stream")
            }
            print("F1_AX tv_track_action_label type=\(menuTitle) label=\(action.label) value=\(action.value ?? "")")
            activateNativeTVMenuChoice(action)
            openNativeTVInfoTab()
            let position = app.descendants(matching: .any).matching(identifier: "playbackPosition").firstMatch
            XCTAssertTrue(position.waitForExistence(timeout: 20), "The AVKit info tab should report the playback clock after track replacement")
            let method = app.descendants(matching: .any).matching(identifier: "playbackMethod").firstMatch
            XCTAssertTrue(method.waitForExistence(timeout: 10))
            expectation(for: NSPredicate(format: "label != %@", "Preparing playback…"), evaluatedWith: method)
            waitForExpectations(timeout: 45)
            if let streamIndex = preferDirect ? config[directKey] : nil {
                let verifyTrigger = nativeTVTransportMenu(menuTitle)
                XCTAssertTrue(verifyTrigger.waitForExistence(timeout: 10), "The native \(menuTitle) menu should reopen for selected-state verification")
                activateNativeTVMenuChoice(verifyTrigger)
                let verifyQuery = app.descendants(matching: .any)
                let selectedByIdentifier = verifyQuery.matching(identifier: prefix + streamIndex).firstMatch
                let selectedAction = selectedByIdentifier.waitForExistence(timeout: 2)
                    ? selectedByIdentifier
                    : verifyQuery.matching(NSPredicate(format: "label MATCHES[c] %@", "^(selected,\\s*)?Stream \(streamIndex)(\\s·|$).*" )).firstMatch
                XCTAssertTrue(selectedAction.waitForExistence(timeout: 10), "The reopened native menu should show configured Stream \(streamIndex)")
                XCTAssertTrue(nativeTVTrackLabel(selectedAction.label, matchesStream: streamIndex), "The reopened native menu must expose the exact configured stream")
                let selectedValue = selectedAction.value as? String
                let selectedInAX = selectedAction.isSelected || ["selected", "on", "1"].contains(selectedValue?.lowercased() ?? "")
                    || selectedAction.label.range(of: "^selected,\\s*", options: [.regularExpression, .caseInsensitive]) != nil
                print("F1_AX tv_track_selected_state type=\(menuTitle) stream=\(streamIndex) isSelected=\(selectedAction.isSelected) value=\(selectedValue ?? "<unexposed>")")
                capture("Native TV \(menuTitle) selected Stream \(streamIndex) checkmark")
                if !selectedInAX { print("F1_AX tv_track_selected_state_unexposed=true; retained native menu screenshot for visual review") }
                XCTAssertTrue(selectedInAX, "AVKit should expose the selected state of configured Stream \(streamIndex); screenshot retained for independent visual review")
                XCUIRemote.shared.press(.menu)
                openNativeTVInfoTab()
            }
            capture("Native TV \(menuTitle) choice applied")
        }
        openNativeTVInfoTab()
        XCTAssertTrue(app.descendants(matching: .any).matching(identifier: "playbackMethod").firstMatch.waitForExistence(timeout: 10))
        XCTAssertTrue(app.descendants(matching: .any).matching(identifier: "playbackPosition").firstMatch.exists)
        capture("Native TV Pictureshow info panel")
    }
    private func nativeTVTrackLabel(_ label: String, matchesStream index: String) -> Bool {
        label.range(of: "^(selected,\\s*)?Stream \(NSRegularExpression.escapedPattern(for: index))(\\s·|$)",
                    options: [.regularExpression, .caseInsensitive]) != nil
    }
    /// Bracket each Info-free video frame with the same actual paused Info clock.
    /// One Menu uses the observed reopen path; pixels, rather than AX focus
    /// proxies, establish cue/control visibility in the Info-free frame.
    private func captureNativeTVExternalSubtitlePreview(_ name: String, pausedSeconds: Int) {
        guard let initial = tvPlayerSnapshot(), let before = tvInfoObservation(initial),
              before.method == "Direct play", parsedClockSeconds(before.clock) == pausedSeconds else {
            XCTFail("Each caption preview must start from Direct Info at the actual paused caption clock")
            return
        }
        print("F2_TV_CAPTION_PREVIEW backStep=1 requireInfoFreePlayer=true")
        XCUIRemote.shared.press(.menu)
        Thread.sleep(forTimeInterval: 0.25)
        let deadline = ProcessInfo.processInfo.systemUptime + 2
        var infoAbsent = false
        let infoIDs: Set<String> = ["playbackMethod", "playbackPosition", "seekBackward", "seekForward"]
        while ProcessInfo.processInfo.systemUptime < deadline {
            if let video = tvPlayerSnapshot(), video.first(identifier: "pictureshowNativePlayer") != nil,
               !video.nodes.contains(where: { infoIDs.contains($0.identifier) }) {
                infoAbsent = true
                print("F2_TV_CAPTION_PREVIEW infoAbsent=true nativePlayer=true priorInfoClock=\(before.clock) snapshot=true")
                break
            }
            Thread.sleep(forTimeInterval: 0.1)
        }
        guard infoAbsent else {
            diagnoseTVInfo(step: "caption preview still contains Info")
            XCTFail("Caption comparison must retain the native player without any custom Info fields")
            return
        }
        captureFullScreen(name)
        guard let restored = openNativeTVInfoTab() else { return }
        XCTAssertEqual(restored.method, "Direct play")
        XCTAssertEqual(parsedClockSeconds(restored.clock), pausedSeconds,
                       "Hiding native controls must retain the same actual paused caption clock")
    }
    private func leaveNativeTVPlayer(returningTo play: XCUIElement) {
        guard tvPlayerSnapshot() != nil else {
            XCTFail("The identified native AVKit presentation should be visible before sending Menu")
            return
        }
        func detailHasReturned() -> Bool {
            guard let root = try? app.snapshot() else { return false }
            let nodes = tvSnapshotNodes(root)
            let playerMarkers: Set<String> = ["pictureshowNativePlayer", "AVInfoMenuCollection", "playbackMethod", "playbackPosition"]
            guard !nodes.contains(where: { playerMarkers.contains($0.identifier) }) else { return false }
            return nodes.contains {
                ["play", "startOver"].contains($0.identifier) && $0.elementType == .button && $0.isEnabled
                    && $0.frame.width > 0 && $0.frame.height > 0 && root.frame.contains($0.frame)
            }
        }
        var returned = false
        for attempt in 0..<5 {
            // A disappearing AX marker can be AVKit's panel transition, not a
            // completed dismissal. Wait for a positive surface before another Menu.
            let settleDeadline = ProcessInfo.processInfo.systemUptime + 1.5
            while tvPlayerSnapshot() == nil && !detailHasReturned()
                    && ProcessInfo.processInfo.systemUptime < settleDeadline {
                Thread.sleep(forTimeInterval: 0.15)
            }
            if detailHasReturned() { returned = true; break }
            guard tvPlayerSnapshot() != nil else { break }
            // The detail opener is obscured by AVKit until Menu dismisses it.
            // Resolving its hittability/focus here can fail before the Back input.
            print("F1_TV_BACK step=\(attempt + 1) playerPresent=true")
            XCUIRemote.shared.press(.menu)
            let deadline = ProcessInfo.processInfo.systemUptime + 2
            while ProcessInfo.processInfo.systemUptime < deadline {
                // Closing Info briefly drops both player AX representations before
                // the transport returns. Only a positive catalog action can end
                // this wait; a transient missing marker cannot skip later Menu inputs.
                if detailHasReturned() { returned = true; break }
                Thread.sleep(forTimeInterval: 0.25)
            }
            if returned { break }
        }
        // Native dismissal may wait for its final progress/Stop report. Once all
        // Back inputs are sent, allow completion without sending extra inputs.
        let finalDeadline = ProcessInfo.processInfo.systemUptime + 5
        while !returned && ProcessInfo.processInfo.systemUptime < finalDeadline {
            if detailHasReturned() { returned = true; break }
            Thread.sleep(forTimeInterval: 0.25)
        }
        captureFullScreen("Native TV after Menu before detail focus verification")
        XCTAssertTrue(returned, "Native Menu should return to a visible detail action, not an intermediate AVKit transition")
        XCTAssertNil(tvPlayerSnapshot(), "Native Menu should dismiss the identified AVKit player presentation")
        XCTAssertTrue(play.waitForExistence(timeout: 15), "The original detail Play action should reappear after native dismissal")
        XCTAssertTrue(play.isHittable, "The original detail Play action should be hittable after native dismissal")
        XCTAssertTrue(play.hasFocus, "Native Menu dismissal should restore focus to the original detail Play action")
    }
    private func probeNativeTVHomeFocusOnce() {
        XCTAssertTrue(hasHomeRoot(timeout: 10), "The focused Home probe must start on the authenticated Home root")
        app.activate()
        capture("Native TV Home focus probe after activate")
        XCUIRemote.shared.press(.down)
        let focused = app.descendants(matching: .any).allElementsBoundByIndex.filter(\.hasFocus)
            .prefix(8)
            .map { "type=\($0.elementType.rawValue) id=\($0.identifier) label=\($0.label) frame=\($0.frame)" }
        print("F1_TV_HOME_FOCUS_PROBE direction=down focused={\(focused.joined(separator: " | "))}")
        capture("Native TV Home focus probe after one Down")
    }
    #endif
    private func reveal(_ element: XCUIElement) {
        var lastFocusedDescription = ""
        for step in 0..<35 {
            if element.exists, element.frame.width > 1, element.frame.height > 1, element.frame.intersects(app.frame), element.isHittable { return }
            #if os(tvOS)
            XCUIRemote.shared.press(.down)
            let focused = app.buttons.allElementsBoundByIndex.first { $0.hasFocus }
            let description = focused.map { "id=\($0.identifier) label=\($0.label) frame=\($0.frame)" } ?? "no-accessible-focused-button"
            if description != lastFocusedDescription {
                let targetDescription: String
                if element.exists {
                    targetDescription = "id=\(element.identifier) label=\(element.label) frame=\(element.frame) hittable=\(element.isHittable)"
                } else {
                    targetDescription = "AXTargetNotLoaded"
                }
                print("F1_TV_REVEAL step=\(step + 1) target={\(targetDescription)} focus={\(description)}")
                lastFocusedDescription = description
            }
            #else
            if element.exists && element.frame.maxY < 130 { app.swipeDown() }
            else { app.swipeUp() }
            #endif
        }
    }
    private func activate(_ element: XCUIElement) {
        XCTAssertTrue(element.waitForExistence(timeout: 10))
        #if os(tvOS)
        // Native focus remains the activation mechanism. When a target is below a header but
        // outside its horizontal column, align left/right first; Down alone can stay trapped
        // on the trailing sort control. If two presses leave focus unchanged, try the other axis.
        var unchangedFocusCount = 0
        var previousAxis = ""
        var didSortHeaderProbe = false
        var visitedFocusDirections = Set<String>()
        for _ in 0..<45 {
            if element.hasFocus { XCUIRemote.shared.press(.select); return }
            let focused = app.descendants(matching: .any).allElementsBoundByIndex.first { $0.hasFocus }
            let current = focused?.frame ?? focusedFrame()
            // SwiftUI Form and List rows may expose focus on their containing cell rather than
            // the nested Button. Select the cell when it already contains the target control.
            if let current,
               current.contains(CGPoint(x: element.frame.midX, y: element.frame.midY)),
               current.height < element.frame.height * 3 {
                XCUIRemote.shared.press(.select)
                return
            }
            let currentLabel = focused?.label ?? ""
            if !didSortHeaderProbe, element.identifier.hasPrefix("item-"),
               ["Ascending", "Title", "Date added", "Release date"].contains(currentLabel) {
                didSortHeaderProbe = true
                for probeStep in 0..<4 {
                    let probeFocus = app.descendants(matching: .any).allElementsBoundByIndex.first { $0.hasFocus }
                    let probeLabel = probeFocus?.label ?? ""
                    let probeFrame = probeFocus?.frame ?? focusedFrame()
                    if probeLabel == "Title" { break }
                    XCUIRemote.shared.press(.left)
                    Thread.sleep(forTimeInterval: 0.15)
                    let after = app.descendants(matching: .any).allElementsBoundByIndex.first { $0.hasFocus }
                    let afterFrame = after?.frame ?? focusedFrame()
                    print("F1_TV_SORT_PROBE step=\(probeStep + 1) from=\(probeLabel) frame=\(probeFrame.map { String(describing: $0) } ?? "<none>") direction=left to=\(after?.label ?? "<none>") frame=\(afterFrame.map { String(describing: $0) } ?? "<none>") target=\(element.identifier) targetFrame=\(element.frame)")
                    if after?.label == probeLabel { break }
                }
                let beforeDown = app.descendants(matching: .any).allElementsBoundByIndex.first { $0.hasFocus }
                let beforeDownFrame = beforeDown?.frame ?? focusedFrame()
                XCUIRemote.shared.press(.down)
                Thread.sleep(forTimeInterval: 0.15)
                let afterDown = app.descendants(matching: .any).allElementsBoundByIndex.first { $0.hasFocus }
                let afterDownFrame = afterDown?.frame ?? focusedFrame()
                print("F1_TV_SORT_PROBE direction=down from=\(beforeDown?.label ?? "<none>") frame=\(beforeDownFrame.map { String(describing: $0) } ?? "<none>") to=\(afterDown?.label ?? "<none>") frame=\(afterDownFrame.map { String(describing: $0) } ?? "<none>") target=\(element.identifier) targetFrame=\(element.frame)")
            }
            let refreshedFocus = app.descendants(matching: .any).allElementsBoundByIndex.first { $0.hasFocus }
            let refreshedFrame = refreshedFocus?.frame ?? focusedFrame()
            let focusKey = refreshedFocus.map { "\($0.elementType.rawValue):\($0.identifier):\($0.label)" }
                .map { "\($0):\(refreshedFrame.map { String(describing: $0) } ?? "<no-frame>")" }
                ?? refreshedFrame.map { "frame:\($0)" }
                ?? "<no-focus-exposed>"
            let dx = refreshedFrame.map { element.frame.midX - $0.midX } ?? 0
            let dy = refreshedFrame.map { element.frame.midY - $0.midY } ?? 100
            let horizontallyAligned = refreshedFrame.map { $0.minX < element.frame.maxX && element.frame.minX < $0.maxX } ?? false
            let verticalDirection: XCUIRemote.Button = dy > 0 ? .down : .up
            var direction = verticalDirection
            var axis = "vertical"

            if unchangedFocusCount >= 2 {
                if previousAxis == "horizontal" {
                    direction = verticalDirection
                    axis = "vertical-after-stall"
                } else {
                    direction = dx > 0 ? .right : .left
                    axis = "horizontal-after-stall"
                }
                unchangedFocusCount = 0
            } else if let refreshedFrame, !horizontallyAligned, element.frame.midY > refreshedFrame.midY + 30 {
                direction = dx > 0 ? .right : .left
                axis = "horizontal-align-before-down"
            } else if abs(dy) > 30 {
                direction = verticalDirection
            } else {
                direction = dx > 0 ? .right : .left
                axis = "horizontal"
            }

            let visitKey = "\(focusKey)|\(axis)|\(String(describing: direction))"
            if visitedFocusDirections.contains(visitKey) {
                if axis.hasPrefix("horizontal") {
                    direction = verticalDirection
                    axis = "vertical-cycle-break"
                } else {
                    direction = dx > 0 ? .right : .left
                    axis = "horizontal-cycle-break"
                }
            }
            visitedFocusDirections.insert("\(focusKey)|\(axis)|\(String(describing: direction))")
            print("F1_TV_NAV focus=\(focusKey) focusFrame=\(refreshedFrame.map { String(describing: $0) } ?? "<none>") targetID=\(element.identifier) targetLabel=\(element.label) targetFrame=\(element.frame) direction=\(axis):\(String(describing: direction))")
            XCUIRemote.shared.press(direction)
            Thread.sleep(forTimeInterval: 0.15)
            let updatedFocused = app.descendants(matching: .any).allElementsBoundByIndex.first { $0.hasFocus }
            let updatedFrame = updatedFocused?.frame ?? focusedFrame()
            let updatedKey = updatedFocused.map { "\($0.elementType.rawValue):\($0.identifier):\($0.label):\(updatedFrame.map { String(describing: $0) } ?? "<no-frame>")" }
                ?? updatedFrame.map { "frame:\($0)" }
                ?? "<no-focus-exposed>"
            if updatedKey == focusKey {
                unchangedFocusCount += 1
            } else {
                print("F1_TV_NAV_FOCUS_CHANGED from=\(focusKey) to=\(updatedKey) targetFrame=\(element.frame)")
                unchangedFocusCount = 0
            }
            previousAxis = axis.hasPrefix("horizontal") ? "horizontal" : "vertical"
        }
        let focused = app.buttons.allElementsBoundByIndex.filter(\.hasFocus)
            .map { "id=\($0.identifier) label=\($0.label) frame=\($0.frame)" }
        let visibleButtons = app.buttons.allElementsBoundByIndex.filter(\.isHittable).prefix(20)
            .map { "id=\($0.identifier) label=\($0.label) frame=\($0.frame)" }
        print("F1_TV_ACTIVATE_FAILED targetID=\(element.identifier) targetLabel=\(element.label) targetFrame=\(element.frame) targetHittable=\(element.isHittable) focusedButtons={\(focused.joined(separator: " | "))}")
        print("F1_TV_VISIBLE_BUTTONS {\(visibleButtons.joined(separator: " | "))}")
        if element.identifier.hasPrefix("item-") || element.identifier.hasPrefix("season-") {
            capture("Native TV catalog focus after remote attempts")
        }
        XCTFail("Remote could not focus \(element.identifier)")
        #else
        element.tap()
        #endif
    }
    #if os(tvOS)
    private func focusedFrame() -> CGRect? {
        let expression = try! NSRegularExpression(pattern: #"\{\{(-?[0-9.]+), (-?[0-9.]+)\}, \{([0-9.]+), ([0-9.]+)\}\}"#)
        return app.debugDescription.split(separator: "\n").filter { $0.contains(", Focused") }.compactMap { raw -> CGRect? in
            let line = String(raw)
            guard let match = expression.firstMatch(in: line, range: NSRange(line.startIndex..., in: line)) else { return nil }
            let values = (1...4).compactMap { Range(match.range(at: $0), in: line).flatMap { Double(line[$0]) } }
            guard values.count == 4 else { return nil }
            return CGRect(x: values[0], y: values[1], width: values[2], height: values[3])
        }.min { $0.width * $0.height < $1.width * $1.height }
    }
    #endif
    private func back() {
        #if os(tvOS)
        XCUIRemote.shared.press(.menu)
        #else
        app.navigationBars.buttons.element(boundBy: 0).tap()
        #endif
    }
    private func returnHome() {
        if hasHomeRoot(timeout: 1) { return }
        #if os(tvOS)
        for _ in 0..<10 where !hasHomeRoot(timeout: 1) { XCUIRemote.shared.press(.menu) }
        #else
        if app.buttons["closePlayer"].exists { activate(app.buttons["closePlayer"]) }
        // The four tabs keep separate NavigationStacks; selecting Home alone does not pop its
        // current Accounts/Settings/Library route. Dismiss input first, switch tabs, then pop
        // only until Home's own toolbar is visible.
        if app.keyboards.firstMatch.exists {
            let submit = app.keyboards.buttons["Search"].firstMatch
            if submit.exists { activate(submit) }
        }
        let home = app.buttons["Home"]
        if home.waitForExistence(timeout: 5) { activate(home) }
        for _ in 0..<10 where !hasHomeRoot(timeout: 1) { back() }
        #endif
        XCTAssertTrue(hasHomeRoot(timeout: 5), "Navigation should return to Home with Search or Settings available")
    }

// Private insertion draft for AppleSliceUITests.swift (methods belong inside
// final class AppleSliceUITests so its existing private UI helpers are visible).
// Requires f5-demo-api-proxy.py and the runner's loopback proxy config.

// MARK: - Private F5 proxy controls

private func waitUntil(timeout: TimeInterval, _ condition: () -> Bool) -> Bool {
    let deadline = ProcessInfo.processInfo.systemUptime + timeout
    repeat {
        if condition() { return true }
        Thread.sleep(forTimeInterval: 0.1)
    } while ProcessInfo.processInfo.systemUptime < deadline
    return condition()
}

private func f5ProxyURL(_ suffix: String) throws -> URL {
    let raw = try XCTUnwrap(config["server"])
    let base = try XCTUnwrap(URL(string: raw))
    XCTAssertEqual(base.scheme, "http")
    XCTAssertEqual(base.host, "127.0.0.1", "F5 controls are loopback only")
    return base.appendingPathComponent("__f5").appendingPathComponent(suffix)
}

private func f5ProxyCall(_ suffix: String, body: [String: Any]? = nil) async throws -> [String: Any] {
    var request = URLRequest(url: try f5ProxyURL(suffix))
    request.httpMethod = body == nil ? "GET" : "POST"
    request.setValue("application/json", forHTTPHeaderField: "Accept")
    if let body {
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
    }
    let (data, response) = try await URLSession.shared.data(for: request)
    let status = (response as? HTTPURLResponse)?.statusCode
    XCTAssertEqual(status, 200, "F5 loopback control request must succeed")
    return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
}

private func f5Status() async throws -> [String: Any] {
    try await f5ProxyCall("status")
}

private func f5Arm(_ route: String, action: String, count: Int = 1, status: Int = 503) async throws {
    let body: [String: Any] = ["route": route, "action": action, "count": count, "status": status]
    let reply = try await f5ProxyCall("fault", body: body)
    XCTAssertEqual(reply["armed"] as? Bool, true)
}

private func f5Release(_ route: String, preservePolicy: Bool = false) async throws {
    let reply = try await f5ProxyCall("release", body: ["route": route, "preservePolicy": preservePolicy])
    XCTAssertEqual(reply["route"] as? String, route)
}

private func f5Pending(_ route: String, in status: [String: Any]) -> Int {
    (status["pendingHeldRequests"] as? [String: Int])?[route] ?? 0
}

private func f5Seen(_ key: String, in status: [String: Any]) -> Int {
    ((status["counts"] as? [String: [String: Int]])?[key])?["seen"] ?? 0
}

private func f5InjectedFailures(_ route: String, in status: [String: Any]) -> Int {
    let rows = status["injected"] as? [[String: Any]] ?? []
    return rows.filter {
        $0["route"] as? String == route && $0["status"] as? Int == 503
    }.count
}

private func f5WaitForPending(_ route: String, count: Int, timeout: TimeInterval) async throws -> Bool {
    let deadline = ProcessInfo.processInfo.systemUptime + timeout
    while ProcessInfo.processInfo.systemUptime < deadline {
        if f5Pending(route, in: try await f5Status()) >= count { return true }
        try await Task.sleep(nanoseconds: 100_000_000)
    }
    return false
}

private func f5WaitForSeen(_ key: String, count: Int, timeout: TimeInterval) async throws -> Bool {
    let deadline = ProcessInfo.processInfo.systemUptime + timeout
    while ProcessInfo.processInfo.systemUptime < deadline {
        if f5Seen(key, in: try await f5Status()) >= count { return true }
        try await Task.sleep(nanoseconds: 100_000_000)
    }
    return false
}

private func f5WaitForStoppedFailures(_ count: Int, timeout: TimeInterval) async throws -> Bool {
    let deadline = ProcessInfo.processInfo.systemUptime + timeout
    while ProcessInfo.processInfo.systemUptime < deadline {
        if f5InjectedFailures("stopped_report", in: try await f5Status()) >= count { return true }
        try await Task.sleep(nanoseconds: 100_000_000)
    }
    return false
}

// MARK: - F5 real demo detail recovery

@MainActor
func testF5DemoHeldWatchedRequestIsSingleAndReadRetryRecovers() async throws {
    let itemID = try XCTUnwrap(config["completionID"] ?? config["captionID"])
    ensureSignedIn()
    app.terminate()
    app.launchEnvironment["PICTURESHOW_TEST_DETAIL_ID"] = itemID
    app.launch()

    let watched = app.buttons["watched"]
    let favorite = app.buttons["favorite"]
    XCTAssertTrue(watched.waitForExistence(timeout: 25))
    XCTAssertTrue(favorite.waitForExistence(timeout: 10))
    XCTAssertTrue(waitUntil(timeout: 10) { ["Add favorite", "Remove favorite"].contains(favorite.label) })
    let favoriteAfterToggle = favorite.label == "Add favorite" ? "Remove favorite" : "Add favorite"
    let initialStatus = try await f5Status()
    let initialWrites = f5Seen("POST:played_write", in: initialStatus)
    capture("F5 before favorite refresh and queued Watched")

    // A successful unrelated Favorite command starts a real authoritative item
    // refresh. Hold that GET so the following Watched press must stay queued.
    try await f5Arm("item_detail", action: "hold", count: 1)
    var releaseNeeded = true
    defer {
        if releaseNeeded { Task { try? await f5Release("item_detail") } }
    }
    activate(favorite)
    XCTAssertTrue(waitUntil(timeout: 10) { favorite.label == favoriteAfterToggle })
    let favoriteReadHeld = try await f5WaitForPending("item_detail", count: 1, timeout: 15)
    XCTAssertTrue(favoriteReadHeld, "The Favorite action's item refresh should be held")
    capture("F5 favorite refresh held before Watched press")

    activate(watched)
    XCTAssertTrue(waitUntil(timeout: 5) { watched.label == "Watched already requested" },
                  "The accepted Watched press should remain visibly requested while queued")
    XCTAssertTrue(watched.isEnabled, "A held pending action must remain activatable")
    activate(watched)
    XCTAssertTrue(waitUntil(timeout: 2) { watched.label == "Watched already requested" },
                  "A repeated press must not create a second queued intent")
    let beforeRelease = try await f5Status()
    XCTAssertEqual(f5Seen("POST:played_write", in: beforeRelease), initialWrites,
                   "Watched cannot reach Jellyfin before its authoritative refresh completes")
    capture("F5 held Favorite read retains one queued Watched intent")

    // Arm the next GET before unblocking the Favorite read. The single queued
    // Watched write runs after that read, then its own refresh receives a 503.
    try await f5Arm("item_detail", action: "hold_fail", count: 1, status: 503)
    try await f5Release("item_detail", preservePolicy: true)
    let watchedReadHeld = try await f5WaitForPending("item_detail", count: 1, timeout: 20)
    XCTAssertTrue(watchedReadHeld, "The post-Watched authoritative refresh should be held then fail")
    let oneWatchedWrite = try await f5WaitForSeen("POST:played_write", count: initialWrites + 1, timeout: 10)
    XCTAssertTrue(oneWatchedWrite)
    let failedReadHeld = try await f5Status()
    XCTAssertEqual(f5Seen("POST:played_write", in: failedReadHeld), initialWrites + 1,
                   "Exactly one Watched write should precede the failed refresh")
    XCTAssertEqual(f5InjectedFailures("item_detail", in: failedReadHeld), 0,
                   "The read fault is injected only when its hold is released")
    capture("F5 queued Watched wrote once and its refresh is held")

    try await f5Release("item_detail")
    releaseNeeded = false
    let retry = app.buttons["detailRetry"]
    XCTAssertTrue(retry.waitForExistence(timeout: 30), "The held read should produce the normal detail Retry state")
    let retryValue = try XCTUnwrap(retry.value as? String)
    let prefix = "Retry. Loading failed. "
    XCTAssertTrue(retryValue.hasPrefix(prefix))
    let reason = String(retryValue.dropFirst(prefix.count))
    XCTAssertFalse(reason.isEmpty)
    XCTAssertTrue(app.staticTexts[reason].waitForExistence(timeout: 5))
    capture("F5 queued Watched truthful read failure")

    let priorDetailReads = f5Seen("GET:item_detail", in: try await f5Status())
    activate(retry)
    let retryReadStarted = try await f5WaitForSeen("GET:item_detail", count: priorDetailReads + 1, timeout: 10)
    XCTAssertTrue(retryReadStarted)
    XCTAssertTrue(waitUntil(timeout: 20) { !retry.exists && watched.value as? String == "Watched" })
    let finalStatus = try await f5Status()
    XCTAssertEqual(f5Seen("POST:played_write", in: finalStatus), initialWrites + 1,
                   "Retrying the authoritative read must not repeat the watched write")
    capture("F5 fresh authoritative Watched state after Retry")
    print("F5_DETAIL_HELD_WATCHED queued=true duplicateGuarded=true writes=1 retryRead=true")
}

// MARK: - F5 real demo completion projection recovery

@MainActor
func testF5DemoFailedCompletionWithdrawsProjectionWhileFreshReadIsHeld() async throws {
    let itemID = try XCTUnwrap(config["completionID"])
    let duration = Int(config["completionDurationSeconds"] ?? "60") ?? 60
    ensureSignedIn()
    app.terminate()
    app.launchEnvironment["PICTURESHOW_TEST_DETAIL_ID"] = itemID
    app.launch()
    let play = app.buttons["play"]
    XCTAssertTrue(play.waitForExistence(timeout: 25))
    XCTAssertTrue(play.label.hasPrefix("Resume from "), "The API harness must seed completionID at forty seconds before launch")
    XCTAssertEqual(parsedClockSeconds(play.label.replacingOccurrences(of: "Resume from ", with: "")), 40)
    try await f5Arm("stopped_report", action: "hold_fail", count: 1, status: 503)
    var stopReleaseNeeded = true
    var detailReleaseNeeded = false
    defer {
        if stopReleaseNeeded { Task { try? await f5Release("stopped_report") } }
        if detailReleaseNeeded { Task { try? await f5Release("item_detail") } }
    }
    activate(play)

    #if os(tvOS)
    _ = try XCTUnwrap(waitForTVPlayerClock(identifier: "AXElapsedTime", minimum: 40, maximum: duration - 1,
                                           timeout: 45, metric: "f5_completion_resume"))
    _ = try XCTUnwrap(waitForTVPlayerClock(identifier: "AXElapsedTime", minimum: duration - 2, maximum: duration + 2,
                                           timeout: 35, metric: "f5_completion_natural_eos"))
    #else
    let position = app.staticTexts["playbackPosition"]
    XCTAssertTrue(position.waitForExistence(timeout: 90))
    XCTAssertTrue(waitForPlaybackClock(position, minimum: 40, timeout: 25, metric: "f5_completion_resume"))
    XCTAssertTrue(waitForPlaybackClock(position, minimum: duration - 2, timeout: 35, metric: "f5_completion_natural_eos"))
    #endif
    let stopHeld = try await f5WaitForPending("stopped_report", count: 1, timeout: 20)
    XCTAssertTrue(stopHeld, "Natural EOS must reach the held final Stop report")

    try await f5Arm("item_detail", action: "hold", count: 10)
    detailReleaseNeeded = true
    #if os(tvOS)
    leaveNativeTVPlayer(returningTo: app.buttons["play"])
    #else
    activate(app.buttons["closePlayer"])
    #endif
    let detailReadHeld = try await f5WaitForPending("item_detail", count: 1, timeout: 15)
    XCTAssertTrue(detailReadHeld, "Returning from playback should start the fresh detail read and hold it")
    let watched = app.buttons["watched"]
    XCTAssertTrue(watched.waitForExistence(timeout: 15))
    XCTAssertTrue(waitUntil(timeout: 10) { watched.label == "Mark unwatched when ready" }, "Successful completion provisionally presents its played projection")
    capture("F5 completion provisional played projection before failed Stop")

    try await f5Arm("stopped_report", action: "fail", count: 1, status: 503)
    // Preserve the second 503 policy while releasing the first held Stop. The
    // proxy's default release clears leftover policy for ordinary cleanup.
    try await f5Release("stopped_report", preservePolicy: true)
    stopReleaseNeeded = false
    let bothStopsFailed = try await f5WaitForStoppedFailures(2, timeout: 20)
    XCTAssertTrue(bothStopsFailed, "The held final Stop and its immediate retry should both fail")
    let stillHeld = try await f5WaitForPending("item_detail", count: 1, timeout: 5)
    XCTAssertTrue(stillHeld, "The authoritative item read must remain held during Stop failure reconciliation")
    XCTAssertTrue(waitUntil(timeout: 15) { watched.label == "Mark watched when ready" }, "A failed completion report withdraws only its provisional Played=true projection")
    let retryProgress = app.buttons["retryProgress"]
    XCTAssertTrue(retryProgress.waitForExistence(timeout: 10))
    XCTAssertEqual(retryProgress.label, "Retry saving progress")
    capture("F5 failed Stop withdraws projection while item read remains held")

    try await f5Release("item_detail")
    detailReleaseNeeded = false
    let afterRelease = try await f5Status()
    XCTAssertEqual(f5Pending("item_detail", in: afterRelease), 0, "The held authoritative detail read is released after evidence capture")
    XCTAssertTrue(waitUntil(timeout: 20) { ["Mark watched", "Mark unwatched"].contains(watched.label) }, "After release, the action must match fresh server UserData")
    print("F5_COMPLETION stopFailures=2 projectionWithdrawn=true retrySavingProgress=true detailReadReleasedAfterCapture=true")
}

    private func capture(_ name: String) { let a = XCTAttachment(screenshot: app.screenshot()); a.name = name; a.lifetime = .keepAlways; add(a) }
    private func captureFullScreen(_ name: String) { let a = XCTAttachment(screenshot: XCUIScreen.main.screenshot()); a.name = name; a.lifetime = .keepAlways; add(a) }
}
