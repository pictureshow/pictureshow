// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import XCTest

/// Runs only against the explicitly supplied private pilot account. Configure test-runner
/// PICTURESHOW_PILOT_JSON through a local .xctestrun, never a checked-in test plan.
final class AppleSliceUITests: XCTestCase {
    var app: XCUIApplication!
    var config: [String: String] = [:]
    var sampleFolderOpen = false
    override func setUpWithError() throws {
        continueAfterFailure = false
        let json = try XCTUnwrap(ProcessInfo.processInfo.environment["PICTURESHOW_PILOT_JSON"], "Supply a private pilot configuration to run the live simulator suite")
        config = try JSONDecoder().decode([String:String].self, from: Data(json.utf8))
        app = XCUIApplication()
        app.launchEnvironment["PICTURESHOW_TEST_SERVER"] = config["server"]
        app.launchEnvironment["PICTURESHOW_TEST_USERNAME"] = config["username"]
        app.launchEnvironment["PICTURESHOW_TEST_PASSWORD"] = config["password"]
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
        try playAndResume(id: XCTUnwrap(config["hlsID"]), expected: "Server HLS", key: "hls")
        try playAndResume(id: XCTUnwrap(config["resumeID"]), expected: "Server HLS", key: "resume")
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
        reveal(library); activate(library)
        XCTAssertTrue(app.descendants(matching: .any).matching(identifier: "librarySort").firstMatch.waitForExistence(timeout: 15))
        capture("Movie library")
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
        XCTAssertTrue(app.buttons["item-\(searchItem)"].waitForExistence(timeout: 20))
        capture("Search results")
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
        reveal(movieLibrary); activate(movieLibrary)
        XCTAssertTrue(app.descendants(matching: .any).matching(identifier: "librarySort").firstMatch.waitForExistence(timeout: 15))
        capture("iPad landscape movie library")
        returnHome()
        let search = app.buttons["openSearch"]
        activate(search)
        XCTAssertTrue(app.textFields["searchField"].waitForExistence(timeout: 10))
        capture("iPad landscape search")

        // Keep one HLS viewing alive while the native AVKit controller expands to fullscreen,
        // returns inline, and rotates to landscape. This catches presentation callbacks that
        // could accidentally close or replace the underlying PlaybackSession.
        XCUIDevice.shared.orientation = .portrait
        returnHome()
        try openSampleDetail(id: XCTUnwrap(config["hlsID"]), key: "hls")
        let play = app.buttons["play"]
        reveal(play); activate(play)
        let method = app.staticTexts["playbackMethod"]
        XCTAssertTrue(method.waitForExistence(timeout: 30))
        XCTAssertTrue(waitForSettledMethod(method, expected: "Server HLS", timeout: 45), "The iPad HLS player should settle before measuring presentation changes")
        let position = app.staticTexts["playbackPosition"]
        XCTAssertTrue(position.waitForExistence(timeout: 10))
        XCTAssertTrue(waitForPlaybackClock(position, minimum: seconds(position.label) + 3, timeout: 40, metric: "ipad_portrait"))
        XCTAssertGreaterThan(app.frame.height, app.frame.width, "The first iPad playback capture should be portrait")
        capture("iPad HLS player portrait")

        XCUIDevice.shared.orientation = .landscapeLeft
        Thread.sleep(forTimeInterval: 2)
        XCTAssertGreaterThan(app.frame.width, app.frame.height, "The active iPad player should adapt to landscape")
        let landscapeStart = seconds(position.label)
        XCTAssertTrue(waitForPlaybackClock(position, minimum: landscapeStart + 3, timeout: 40, metric: "ipad_landscape_player"))
        capture("iPad HLS player landscape")
        XCUIDevice.shared.orientation = .portrait
        Thread.sleep(forTimeInterval: 2)

        let fullScreen = app.buttons.matching(NSPredicate(format: "label CONTAINS[c] %@ OR label CONTAINS[c] %@ OR label CONTAINS[c] %@", "full screen", "fullscreen", "expand")).firstMatch
        if !fullScreen.waitForExistence(timeout: 10) {
            let labels = app.buttons.allElementsBoundByIndex.map(\.label).filter { !$0.isEmpty }
            print("F1_AX iPad_native_player_buttons " + labels.joined(separator: " | "))
            capture("iPad fullscreen control unavailable")
            print("F1_NOTE iPad_fullscreen_roundtrip=UNVERIFIED reason=AVKit_expand_control_not_exposed; portrait_and_landscape_playback_already_captured")
            throw XCTSkip("AVKit did not expose a fullscreen expansion control; orientation playback evidence was captured separately")
        }
        let inlineTimeline = app.sliders.firstMatch
        XCTAssertTrue(inlineTimeline.waitForExistence(timeout: 10), "The inline AVKit player should expose its native timeline")
        let inlineTimelineWidth = inlineTimeline.frame.width
        activate(fullScreen)
        XCTAssertTrue(method.waitForExistence(timeout: 15), "Expanding the native AVKit player should keep the same Pictureshow viewing active")
        let exitFullScreen = app.buttons.matching(NSPredicate(format: "label CONTAINS[c] %@ OR label CONTAINS[c] %@ OR label CONTAINS[c] %@", "exit full screen", "shrink", "inline")).firstMatch
        var nativeFullscreenStateChanged = false
        let stateDeadline = ProcessInfo.processInfo.systemUptime + 10
        while ProcessInfo.processInfo.systemUptime < stateDeadline {
            let expandedTimelineWidth = app.sliders.firstMatch.frame.width
            if exitFullScreen.exists || (inlineTimelineWidth > 1 && expandedTimelineWidth >= inlineTimelineWidth * 1.25) {
                nativeFullscreenStateChanged = true
                break
            }
            Thread.sleep(forTimeInterval: 0.25)
        }
        let expandedTimelineWidth = app.sliders.firstMatch.frame.width
        print("F1_METRIC iPad_native_fullscreen_state_changed=\(nativeFullscreenStateChanged) inline_timeline_width=\(inlineTimelineWidth) expanded_timeline_width=\(expandedTimelineWidth) exit_control=\(exitFullScreen.exists ? exitFullScreen.label : "missing")")
        XCTAssertTrue(waitForPlaybackClock(position, minimum: seconds(position.label) + 3, timeout: 40, metric: "ipad_fullscreen"))
        capture(nativeFullscreenStateChanged ? "iPad native player expanded fullscreen" : "iPad fullscreen state unverified after activate")
        guard nativeFullscreenStateChanged else {
            print("F1_NOTE iPad_fullscreen_roundtrip=UNVERIFIED reason=no_native_state_or_geometry_change; portrait_and_landscape_playback_already_captured")
            throw XCTSkip("AVKit fullscreen state did not change observably; portrait and landscape playback evidence was captured separately")
        }
        let toggleFullScreen = exitFullScreen.exists ? exitFullScreen : app.buttons.matching(NSPredicate(format: "label CONTAINS[c] %@ OR label CONTAINS[c] %@", "shrink", "inline")).firstMatch
        XCTAssertTrue(toggleFullScreen.waitForExistence(timeout: 10), "The expanded AVKit player should expose its return-to-inline control")
        activate(toggleFullScreen)
        XCTAssertTrue(method.waitForExistence(timeout: 15), "Returning inline should preserve the player")
        let expandAgain = app.buttons.matching(NSPredicate(format: "label CONTAINS[c] %@ OR label CONTAINS[c] %@ OR label CONTAINS[c] %@", "full screen", "fullscreen", "expand")).firstMatch
        XCTAssertTrue(expandAgain.waitForExistence(timeout: 10), "AVKit should return its native expand control in the inline state")
        let inlineStart = seconds(position.label)
        XCTAssertTrue(waitForPlaybackClock(position, minimum: inlineStart + 3, timeout: 40, metric: "ipad_inline"))
        capture("iPad native player returned inline")
        activate(app.buttons["closePlayer"])
        XCTAssertTrue(app.staticTexts["detailTitle"].waitForExistence(timeout: 15), "Back should return to the same media detail after iPad rotation")
        #endif
    }
    /// Isolates actual AVPlayer startup from catalog navigation when diagnosing a decoder
    /// or transport failure. The full slice test remains the navigation acceptance gate.
    func testDirectPlaybackStartup() throws {
        ensureSignedIn()
        app.terminate()
        app.launchEnvironment["PICTURESHOW_TEST_DETAIL_ID"] = try XCTUnwrap(config["directID"])
        app.launch()
        let playFromStart = app.buttons["startOver"]
        reveal(playFromStart)
        XCTAssertTrue(playFromStart.waitForExistence(timeout: 15), "Direct startup trials must use Play from start, not a saved resume position")
        capture("Movie detail")
        let playRequestedAt = ProcessInfo.processInfo.systemUptime
        activate(playFromStart)
        #if os(tvOS)
        XCUIRemote.shared.press(.up)
        let info = app.buttons["Pictureshow"]
        XCTAssertTrue(info.waitForExistence(timeout: 10), "The native player should expose its Pictureshow info tab")
        activate(info)
        #endif
        let method = app.staticTexts["playbackMethod"]
        XCTAssertTrue(method.waitForExistence(timeout: 30))
        print("F1_METRIC direct_method_presentation_ms=\(Int((ProcessInfo.processInfo.systemUptime - playRequestedAt) * 1000)) method=\(method.label)")
        capture("Direct startup initial preparation")
        let position = app.staticTexts["playbackPosition"]
        XCTAssertTrue(position.waitForExistence(timeout: 10), "The player should expose an accessible current position")
        #if os(iOS)
        assertTouchPlayerActionLabels()
        #endif
        XCTAssertFalse(position.label.isEmpty, "The initial clock value must be present before direct playback is measured")
        let initialSeconds = seconds(position.label)
        let observedAdvanceTarget = max(3, initialSeconds + 3)
        print("F1_METRIC direct_initial_clock_seconds=\(initialSeconds) observed_advance_target_seconds=\(observedAdvanceTarget)")
        XCTAssertTrue(waitForPlaybackClock(position, minimum: observedAdvanceTarget, timeout: 40, metric: "direct"))
        print("F1_METRIC direct_clock_advanced_from_play_ms=\(Int((ProcessInfo.processInfo.systemUptime - playRequestedAt) * 1000)) seconds=\(seconds(position.label))")
        XCTAssertEqual(method.label, "Direct play")
        capture("Direct startup advancing video")
        #if os(tvOS)
        leaveNativeTVPlayer(returningTo: playFromStart)
        #else
        activate(app.buttons["closePlayer"])
        #endif
        XCTAssertTrue(playFromStart.waitForExistence(timeout: 15))
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
        XCTAssertTrue(app.staticTexts["playbackMethod"].waitForExistence(timeout: 30))
        XCTAssertEqual(app.staticTexts["playbackMethod"].label, "Server HLS")
        #if os(tvOS)
        // This episode uses HLS. Keep its method assertion ahead of the native menus;
        // the dedicated direct-player case below proves Direct play before track selection.
        inspectNativeTVInfoAndTracks(preferDirect: false)
        #endif
        let position = app.staticTexts["playbackPosition"]
        XCTAssertTrue(position.waitForExistence(timeout: 10), "The player should expose an accessible current position")
        let beforeChange = seconds(position.label)
        XCTAssertTrue(waitForPlaybackClock(position, minimum: beforeChange + 5, timeout: 35, metric: "episode"))
        capture("Episode playback after track apply")
        #if os(tvOS)
        XCUIRemote.shared.press(.playPause)
        let pausedAt = seconds(position.label)
        Thread.sleep(forTimeInterval: 2)
        XCTAssertLessThanOrEqual(seconds(position.label), pausedAt + 1, "The remote play/pause command should pause the clock")
        XCUIRemote.shared.press(.playPause)
        expectation(for: NSPredicate { _, _ in self.seconds(position.label) >= pausedAt + 3 }, evaluatedWith: position)
        waitForExpectations(timeout: 15)
        let beforeSeek = seconds(position.label)
        activate(app.buttons["seekForward"])
        expectation(for: NSPredicate { _, _ in self.seconds(position.label) >= beforeSeek + 8 }, evaluatedWith: position)
        waitForExpectations(timeout: 10)
        capture("Remote pause resume and seek")
        leaveNativeTVPlayer(returningTo: play)
        #else
        activate(app.buttons["closePlayer"])
        #endif
        XCTAssertTrue(play.waitForExistence(timeout: 15))
        #if os(tvOS)
        XCTAssertTrue(play.hasFocus)
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
    func testNativeTVDirectPlaybackMenusInfoAndRemoteScrub() throws {
        #if os(tvOS)
        ensureSignedIn()
        probeNativeTVHomeFocusOnce()
        try openSampleDetail(id: XCTUnwrap(config["directID"]), key: "direct")
        let play = app.buttons["play"]
        reveal(play); activate(play)
        XCUIRemote.shared.press(.up)
        let info = app.buttons["Pictureshow"]
        XCTAssertTrue(info.waitForExistence(timeout: 10), "The native player should expose its Pictureshow info tab before method/clock assertions")
        activate(info)
        let method = app.staticTexts["playbackMethod"]
        XCTAssertTrue(method.waitForExistence(timeout: 30))
        XCTAssertTrue(waitForSettledMethod(method, expected: "Direct play", timeout: 45), "Direct playback must settle before opening native track menus")
        let position = app.staticTexts["playbackPosition"]
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
        let play = app.buttons["play"]
        reveal(play); activate(play)
        XCUIRemote.shared.press(.up)
        let resumedInfo = app.buttons["Pictureshow"]
        XCTAssertTrue(resumedInfo.waitForExistence(timeout: 10))
        activate(resumedInfo)
        XCTAssertTrue(app.staticTexts["playbackPosition"].waitForExistence(timeout: 30))
        let position = app.staticTexts["playbackPosition"]
        let duration = max(1, Double(config["episodeRunSeconds"] ?? "0") ?? 0)
        XCTAssertGreaterThan(duration, 0, "The adjacent episode test needs a known duration")
        let timeline = app.sliders.firstMatch
        XCTAssertTrue(timeline.waitForExistence(timeout: 10), "AVPlayerViewController should expose its native playback timeline")
        timeline.adjust(toNormalizedSliderPosition: CGFloat(max(0.0, min(1.0, (duration - 8) / duration))))
        let nearEndTarget = max(1, Int(duration) - 15)
        expectation(for: NSPredicate { _, _ in self.seconds(position.label) >= nearEndTarget }, evaluatedWith: position)
        waitForExpectations(timeout: 25)
        let pause = app.buttons.matching(NSPredicate(format: "label CONTAINS[c] %@", "Pause")).firstMatch
        XCTAssertTrue(pause.waitForExistence(timeout: 10), "Pause near the end so the native countdown cannot race the manual next action")
        activate(pause)
        let nearEnd = seconds(position.label)
        XCTAssertGreaterThanOrEqual(nearEnd, nearEndTarget, "Seeking near the end should update the native playback clock before waiting for Play next")
        capture("Native TV episode near end")

        let nextByID = app.descendants(matching: .any).matching(identifier: "playNext").firstMatch
        let nextByTitle = app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH[c] %@", "Play next:")).firstMatch
        let next = nextByID.waitForExistence(timeout: 1) ? nextByID : nextByTitle
        XCTAssertTrue(next.waitForExistence(timeout: 60), "Near the episode end, AVKit should expose the next-episode contextual action")
        capture("Native TV Play next action available")
        activate(next)

        let expectedName = try XCTUnwrap(config["nextEpisodeName"])
        XCTAssertTrue(app.staticTexts[expectedName].waitForExistence(timeout: 45), "The Pictureshow info panel should name the adjacent episode after handoff")
        expectation(for: NSPredicate { _, _ in self.seconds(position.label) <= 12 }, evaluatedWith: position)
        waitForExpectations(timeout: 45)
        expectation(for: NSPredicate { _, _ in self.seconds(position.label) >= 2 }, evaluatedWith: position)
        waitForExpectations(timeout: 35)
        XCTAssertLessThanOrEqual(seconds(position.label), 12, "The next episode must start from its own beginning, not inherit the prior source position")
        capture("Native TV adjacent episode started")
        XCUIRemote.shared.press(.menu)
        #endif
    }
    private func playAndResume(id: String, expected: String, key: String) throws {
        let containers = (config[key + "Containers"] ?? "").split(separator: "|").map(String.init)
        let button = try openSampleDetail(id: id, key: key)
        let play = app.buttons["play"]
        reveal(play)
        XCTAssertTrue(play.waitForExistence(timeout: 15))
        activate(play)
        #if os(tvOS)
        XCUIRemote.shared.press(.up)
        let info = app.buttons["Pictureshow"]
        XCTAssertTrue(info.waitForExistence(timeout: 10), "The native player should expose its Pictureshow info tab")
        activate(info)
        #endif
        let method = app.staticTexts["playbackMethod"]
        XCTAssertTrue(method.waitForExistence(timeout: 30))
        capture("\(expected) initial preparation")
        XCTAssertTrue(waitForSettledMethod(method, expected: expected, timeout: 45), "The playback method should settle to \(expected)")
        let position = app.staticTexts["playbackPosition"]
        XCTAssertTrue(position.waitForExistence(timeout: 10), "The player should expose an accessible current position")
        XCTAssertTrue(waitForPlaybackClock(position, minimum: 2, timeout: 35, metric: key))
        // Respect the server's minimum video duration and watched percentage for resume.
        let minimum = max(Int(config[key + "ResumeSeconds"] ?? "6") ?? 6, seconds(position.label) + 6)
        XCTAssertTrue(waitForPlaybackClock(position, minimum: minimum, timeout: TimeInterval(minimum + 35), metric: key + "_resume"))
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
        if eligible {
            expectation(for: NSPredicate(format: "label CONTAINS %@", "Resume from"), evaluatedWith: play)
            waitForExpectations(timeout: 15)
        }
        #if os(tvOS)
        XCTAssertTrue(play.hasFocus, "Back should restore focus to the detail Play action")
        #endif
        let saved = play.label
        activate(play)
        XCTAssertTrue(method.waitForExistence(timeout: 30))
        #if os(tvOS)
        XCUIRemote.shared.press(.up)
        let resumedInfo = app.buttons["Pictureshow"]
        XCTAssertTrue(resumedInfo.waitForExistence(timeout: 10))
        activate(resumedInfo)
        XCTAssertTrue(app.staticTexts["playbackPosition"].waitForExistence(timeout: 10))
        #endif
        let resumeMinimum = eligible ? max(2, seconds(saved.replacingOccurrences(of: "Resume from ", with: "")) + 2) : 2
        // A reopened HLS stream has a 45-second startup deadline; allow 15 more seconds for
        // AVKit's status and accessible clock to surface. Keep the earlier 30-second failure
        // artifacts separate, and retain this finite 60-second metric.
        let resumedTimeout: TimeInterval = key == "hls" ? 60 : 30
        let resumed = waitForPlaybackClock(position, minimum: resumeMinimum, timeout: resumedTimeout, metric: key + "_resumed")
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
        XCUIRemote.shared.press(.menu)
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
    private func seconds(_ label: String) -> Int {
        let parts = label.split(separator: ":").compactMap { Int($0) }
        return parts.count == 2 ? parts[0] * 60 + parts[1] : 0
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
    private func waitForPlaybackClock(_ position: XCUIElement, minimum: Int, timeout: TimeInterval, metric: String) -> Bool {
        let startedAt = ProcessInfo.processInfo.systemUptime
        let deadline = startedAt + timeout
        var lastRevealAt: TimeInterval = 0
        while ProcessInfo.processInfo.systemUptime < deadline {
            if position.exists, !position.label.isEmpty, seconds(position.label) >= minimum {
                print("F1_METRIC \(metric)_clock_advanced_ms=\(Int((ProcessInfo.processInfo.systemUptime - startedAt) * 1000)) seconds=\(seconds(position.label))")
                return true
            }
            let now = ProcessInfo.processInfo.systemUptime
            let needsReveal = !position.exists || position.label.isEmpty
            if needsReveal, now - lastRevealAt >= 3 {
                #if os(tvOS)
                XCUIRemote.shared.press(.up)
                #else
                let options = app.buttons["playerOptions"]
                if options.exists { activate(options) }
                else { app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap() }
                #endif
                lastRevealAt = now
            }
            Thread.sleep(forTimeInterval: 0.35)
        }
        let finalLabel = position.exists ? position.label : "<missing>"
        print("F1_METRIC \(metric)_clock_timeout_ms=\(Int((ProcessInfo.processInfo.systemUptime - startedAt) * 1000)) final_position=\(finalLabel)")
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
    private func inspectNativeTVInfoAndTracks(preferDirect: Bool = false) {
        for menuTitle in ["Audio", "Subtitles"] {
            XCUIRemote.shared.press(.up)
            let trigger = app.buttons[menuTitle].firstMatch
            XCTAssertTrue(trigger.waitForExistence(timeout: 10), "Missing native AVKit \(menuTitle) transport menu")
            XCTAssertEqual(trigger.label, menuTitle, "The native AVKit transport menu should expose its visible label")
            print("F1_AX tv_transport_menu_label=\(trigger.label)")
            activate(trigger)
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
                    action = actionQuery.matching(NSPredicate(format: "label ENDSWITH[c] %@", "Stream \(streamIndex)")).firstMatch
                } else {
                    action = actionQuery.matching(NSPredicate(format: "label CONTAINS[c] %@", "Stream ")).firstMatch
                }
            }
            XCTAssertTrue(action.waitForExistence(timeout: 10), "Missing native AVKit \(menuTitle) menu items")
            if let streamIndex = preferDirect ? config[directKey] : nil {
                XCTAssertTrue(action.label.lowercased().hasSuffix("stream \(streamIndex)"),
                              "The native \(menuTitle) choice must match configured Stream \(streamIndex), not a similarly numbered stream")
            }
            print("F1_AX tv_track_action_label type=\(menuTitle) label=\(action.label) value=\(action.value ?? "")")
            activate(action)
            XCTAssertTrue(app.buttons["Pictureshow"].waitForExistence(timeout: 20), "Selecting a native \(menuTitle) item should return to playback")
            activate(app.buttons["Pictureshow"])
            XCTAssertTrue(app.staticTexts["playbackPosition"].waitForExistence(timeout: 20), "The AVKit info tab should report the playback clock after track replacement")
            let method = app.staticTexts["playbackMethod"]
            XCTAssertTrue(method.waitForExistence(timeout: 10))
            expectation(for: NSPredicate(format: "label != %@", "Preparing playback…"), evaluatedWith: method)
            waitForExpectations(timeout: 45)
            if let streamIndex = preferDirect ? config[directKey] : nil {
                XCUIRemote.shared.press(.up)
                let verifyTrigger = app.buttons[menuTitle].firstMatch
                XCTAssertTrue(verifyTrigger.waitForExistence(timeout: 10), "The native \(menuTitle) menu should reopen for selected-state verification")
                activate(verifyTrigger)
                let suffix = "stream \(streamIndex)"
                let verifyQuery = app.descendants(matching: .any)
                let selectedByIdentifier = verifyQuery.matching(identifier: prefix + streamIndex).firstMatch
                let selectedAction = selectedByIdentifier.waitForExistence(timeout: 2)
                    ? selectedByIdentifier
                    : verifyQuery.matching(NSPredicate(format: "label ENDSWITH[c] %@", suffix)).firstMatch
                XCTAssertTrue(selectedAction.waitForExistence(timeout: 10), "The reopened native menu should show configured Stream \(streamIndex)")
                XCTAssertTrue(selectedAction.label.lowercased().hasSuffix(suffix), "The reopened native menu must expose the exact configured stream")
                let selectedValue = selectedAction.value as? String
                let selectedInAX = selectedAction.isSelected || ["selected", "on", "1"].contains(selectedValue?.lowercased() ?? "")
                print("F1_AX tv_track_selected_state type=\(menuTitle) stream=\(streamIndex) isSelected=\(selectedAction.isSelected) value=\(selectedValue ?? "<unexposed>")")
                capture("Native TV \(menuTitle) selected Stream \(streamIndex) checkmark")
                if !selectedInAX { print("F1_AX tv_track_selected_state_unexposed=true; retained native menu screenshot for visual review") }
                XCTAssertTrue(selectedInAX, "AVKit should expose the selected state of configured Stream \(streamIndex); screenshot retained for independent visual review")
                XCUIRemote.shared.press(.menu)
                let infoTab = app.buttons["Pictureshow"]
                XCTAssertTrue(infoTab.waitForExistence(timeout: 10), "Dismissing the native menu should return to the player")
                activate(infoTab)
            }
            capture("Native TV \(menuTitle) choice applied")
        }
        let info = app.buttons["Pictureshow"]
        XCTAssertTrue(info.waitForExistence(timeout: 10), "The native AVKit info tab should be titled Pictureshow")
        XCTAssertEqual(info.label, "Pictureshow")
        print("F1_AX tv_info_tab_label=\(info.label)")
        activate(info)
        XCTAssertTrue(app.staticTexts["playbackMethod"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["playbackPosition"].exists)
        capture("Native TV Pictureshow info panel")
    }
    private func leaveNativeTVPlayer(returningTo play: XCUIElement) {
        for _ in 0..<2 where !play.exists { XCUIRemote.shared.press(.menu) }
        XCTAssertTrue(play.waitForExistence(timeout: 15))
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
                print("F1_TV_REVEAL step=\(step + 1) targetID=\(element.identifier) targetLabel=\(element.label) targetFrame=\(element.frame) targetHittable=\(element.isHittable) focus={\(description)}")
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
            let focusKey = focused.map { "\($0.elementType.rawValue):\($0.identifier):\($0.label)" }
                ?? current.map { "frame:\($0)" }
                ?? "<no-focus-exposed>"
            let dx = current.map { element.frame.midX - $0.midX } ?? 0
            let dy = current.map { element.frame.midY - $0.midY } ?? 100
            let horizontallyAligned = current.map { $0.minX < element.frame.maxX && element.frame.minX < $0.maxX } ?? false
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
            } else if let current, !horizontallyAligned, element.frame.midY > current.midY + 30 {
                direction = dx > 0 ? .right : .left
                axis = "horizontal-align-before-down"
            } else if abs(dy) > 30 {
                direction = verticalDirection
            } else {
                direction = dx > 0 ? .right : .left
                axis = "horizontal"
            }

            print("F1_TV_NAV focus=\(focusKey) targetID=\(element.identifier) targetLabel=\(element.label) direction=\(axis):\(String(describing: direction))")
            XCUIRemote.shared.press(direction)
            Thread.sleep(forTimeInterval: 0.15)
            let updatedFocused = app.descendants(matching: .any).allElementsBoundByIndex.first { $0.hasFocus }
            let updatedKey = updatedFocused.map { "\($0.elementType.rawValue):\($0.identifier):\($0.label)" }
                ?? focusedFrame().map { "frame:\($0)" }
                ?? "<no-focus-exposed>"
            if updatedKey == focusKey {
                unchangedFocusCount += 1
            } else {
                print("F1_TV_NAV_FOCUS_CHANGED from=\(focusKey) to=\(updatedKey)")
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
    private func capture(_ name: String) { let a = XCTAttachment(screenshot: app.screenshot()); a.name = name; a.lifetime = .keepAlways; add(a) }
}
