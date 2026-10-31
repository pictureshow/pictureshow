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
        if app.buttons["signOut"].waitForExistence(timeout: 5) { activate(app.buttons["signOut"]) }
        XCTAssertTrue(app.textFields["serverAddress"].waitForExistence(timeout: 10))
        XCTAssertEqual(app.textFields["serverAddress"].label, "Server address")
        activate(app.buttons["connect"])
        XCTAssertTrue(app.buttons["signIn"].waitForExistence(timeout: 15))
        activate(app.buttons["signIn"])
        XCTAssertTrue(app.buttons["signOut"].waitForExistence(timeout: 20))
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
        let seasonID = try XCTUnwrap(config["seasonID"])
        let season = app.buttons["season-\(seasonID)"]
        reveal(season); activate(season)
        let episodeID = try XCTUnwrap(config["episodeID"])
        let episode = app.buttons["item-\(episodeID)"]
        reveal(episode); activate(episode)
        XCTAssertTrue(app.staticTexts["detailTitle"].waitForExistence(timeout: 15))
        capture("Series episode detail")
        let play = app.buttons["play"]
        reveal(play); activate(play)
        #if os(tvOS)
        inspectNativeTVInfoAndTracks(preferDirect: true)
        #else
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
        if app.buttons["signOut"].waitForExistence(timeout: 5) { activate(app.buttons["signOut"]) }
        activate(app.buttons["connect"])
        XCTAssertTrue(app.buttons["quickConnect"].waitForExistence(timeout: 15))
        activate(app.buttons["quickConnect"])
        XCTAssertTrue(app.staticTexts["quickCode"].waitForExistence(timeout: 15))
        let code = app.staticTexts["quickCode"].label
        XCTAssertTrue(code.hasPrefix("Quick Connect code "))
        // The private harness reads the visible code from the live accessibility tree; do not
        // retain this one-time approval code in xcresult attachments or logs.
        if config["approveQuickConnect"] == "true" {
            XCTAssertTrue(app.buttons["signOut"].waitForExistence(timeout: 90))
        } else {
            activate(app.buttons["cancelQuick"])
            XCTAssertTrue(app.buttons["signIn"].waitForExistence(timeout: 10))
        }
    }
    func testNativeTVDirectPlaybackMenusInfoAndRemoteScrub() throws {
        #if os(tvOS)
        ensureSignedIn()
        app.terminate()
        app.launchEnvironment["PICTURESHOW_TEST_DETAIL_ID"] = try XCTUnwrap(config["directID"])
        app.launch()
        let play = app.buttons["play"]
        reveal(play); activate(play)
        inspectNativeTVInfoAndTracks(preferDirect: true)
        let method = app.staticTexts["playbackMethod"]
        XCTAssertTrue(method.exists)
        let position = app.staticTexts["playbackPosition"]
        XCTAssertTrue(position.exists)
        let before = seconds(position.label)
        expectation(for: NSPredicate { _, _ in self.seconds(position.label) >= before + 3 }, evaluatedWith: position)
        waitForExpectations(timeout: 35)
        capture("Native TV player transport")
        if let cue = config["directSubtitleCueSeconds"].flatMap(Int.init) {
            expectation(for: NSPredicate { _, _ in self.seconds(position.label) >= cue }, evaluatedWith: position)
            waitForExpectations(timeout: TimeInterval(cue + 30))
            capture("Native TV selected subtitle cue")
        }

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
        app.terminate()
        app.launchEnvironment["PICTURESHOW_TEST_DETAIL_ID"] = try XCTUnwrap(config["episodeID"])
        app.launch()
        let play = app.buttons["play"]
        reveal(play); activate(play)
        XCUIRemote.shared.press(.up)
        let resumedInfo = app.buttons["Pictureshow"]
        XCTAssertTrue(resumedInfo.waitForExistence(timeout: 10))
        activate(resumedInfo)
        XCTAssertTrue(app.staticTexts["playbackPosition"].waitForExistence(timeout: 30))
        let duration = max(1, Double(config["episodeRunSeconds"] ?? "0") ?? 0)
        XCTAssertGreaterThan(duration, 0, "The adjacent episode test needs a known duration")
        let timeline = app.sliders.firstMatch
        XCTAssertTrue(timeline.waitForExistence(timeout: 10), "AVPlayerViewController should expose its native playback timeline")
        timeline.adjust(toNormalizedSliderPosition: CGFloat(max(0.0, min(1.0, (duration - 8) / duration))))
        capture("Native TV episode near end")

        let next = app.descendants(matching: .any).matching(identifier: "playNext").firstMatch
        XCTAssertTrue(next.waitForExistence(timeout: 60), "Near the episode end, AVKit should expose the next-episode contextual action")
        capture("Native TV Play next action available")
        activate(next)

        let expectedName = try XCTUnwrap(config["nextEpisodeName"])
        XCTAssertTrue(app.staticTexts[expectedName].waitForExistence(timeout: 45), "The Pictureshow info panel should name the adjacent episode after handoff")
        let position = app.staticTexts["playbackPosition"]
        XCTAssertTrue(position.waitForExistence(timeout: 20))
        expectation(for: NSPredicate { _, _ in self.seconds(position.label) >= 2 }, evaluatedWith: position)
        waitForExpectations(timeout: 35)
        XCTAssertLessThanOrEqual(seconds(position.label), 12, "The next episode must start from its own beginning, not inherit the prior source position")
        capture("Native TV adjacent episode started")
        XCUIRemote.shared.press(.menu)
        #endif
    }
    private func playAndResume(id: String, expected: String, key: String) throws {
        let button = app.buttons["item-\(id)"]
        if !sampleFolderOpen {
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
        XCTAssertEqual(method.label, expected)
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
        XCTAssertTrue(waitForPlaybackClock(position, minimum: resumeMinimum, timeout: 30, metric: key + "_resumed"))
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
                _ = cue
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
            if connect.exists { activate(connect) }
        }
        guard signIn.waitForExistence(timeout: 25) else {
            let known = ["connect", "signIn", "serverAddress", "accountsTitle", "addServer", "cancelQuick", "quickCode", "signOut", "openSearch", "openSettings"]
            let visible = app.descendants(matching: .any).allElementsBoundByIndex
                .map(\.identifier).filter { known.contains($0) || $0.hasPrefix("account-") }
            XCTFail("Could not reach password sign-in. Visible safe identifiers: \(visible.joined(separator: ","))")
            return
        }
        activate(signIn)
        XCTAssertTrue(hasHomeRoot(timeout: 30), "Successful sign-in should expose Home's Search or Settings toolbar action")
        returnHome()
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
    /// AVKit hides its overlay after a short idle interval. Keep revealing it while observing a
    /// non-empty clock; a missing or empty accessibility label can never count as progress.
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
            if now - lastRevealAt >= 3 {
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
    /// iOS track actions commit immediately and dismiss the sheet. Reopen it for each choice;
    /// this also avoids relying on a transient toolbar item after AVKit hides its controls.
    private func openTouchTrackPanel() {
        let tracks = app.buttons["playerTracks"]
        if !tracks.exists {
            let options = app.buttons["playerOptions"]
            reveal(options); activate(options)
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
            activate(trigger)
            capture("Native TV \(menuTitle) transport menu")
            let prefix = menuTitle == "Audio" ? "audio-" : "subtitle-"
            let directKey = menuTitle == "Audio" ? "directAudioIndex" : "directSubtitleIndex"
            let preferred = preferDirect ? config[directKey].map { prefix + $0 } : nil
            let actionQuery = app.descendants(matching: .any)
            let action = preferred.map { actionQuery.matching(identifier: $0).firstMatch }
                ?? actionQuery.matching(NSPredicate(format: "identifier BEGINSWITH %@", prefix)).firstMatch
            XCTAssertTrue(action.waitForExistence(timeout: 10), "Missing native AVKit \(menuTitle) menu items")
            activate(action)
            XCTAssertTrue(app.buttons["Pictureshow"].waitForExistence(timeout: 20), "Selecting a native \(menuTitle) item should return to playback")
            activate(app.buttons["Pictureshow"])
            XCTAssertTrue(app.staticTexts["playbackPosition"].waitForExistence(timeout: 20), "The AVKit info tab should report the playback clock after track replacement")
            let method = app.staticTexts["playbackMethod"]
            XCTAssertTrue(method.waitForExistence(timeout: 10))
            expectation(for: NSPredicate(format: "label != %@", "Preparing playback…"), evaluatedWith: method)
            waitForExpectations(timeout: 45)
            capture("Native TV \(menuTitle) choice applied")
        }
        let info = app.buttons["Pictureshow"]
        XCTAssertTrue(info.waitForExistence(timeout: 10), "The native AVKit info tab should be titled Pictureshow")
        activate(info)
        XCTAssertTrue(app.staticTexts["playbackMethod"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["playbackPosition"].exists)
        capture("Native TV Pictureshow info panel")
    }
    private func leaveNativeTVPlayer(returningTo play: XCUIElement) {
        for _ in 0..<2 where !play.exists { XCUIRemote.shared.press(.menu) }
        XCTAssertTrue(play.waitForExistence(timeout: 15))
    }
    #endif
    private func reveal(_ element: XCUIElement) {
        for _ in 0..<35 {
            if element.exists, element.frame.width > 1, element.frame.height > 1, element.frame.intersects(app.frame), element.isHittable { return }
            #if os(tvOS)
            XCUIRemote.shared.press(.down)
            #else
            if element.exists && element.frame.maxY < 130 { app.swipeDown() }
            else { app.swipeUp() }
            #endif
        }
    }
    private func activate(_ element: XCUIElement) {
        XCTAssertTrue(element.waitForExistence(timeout: 10))
        #if os(tvOS)
        // Native focus remains the activation mechanism. The test app's focused controls
        // are navigated by remote; use the shortest bounded search among visible controls.
        for _ in 0..<45 {
            if element.hasFocus { XCUIRemote.shared.press(.select); return }
            let current = app.buttons.allElementsBoundByIndex.first { $0.hasFocus }?.frame ?? focusedFrame()
            if let current {
                // SwiftUI Form rows focus a containing cell rather than their Button child.
                if current.contains(CGPoint(x: element.frame.midX, y: element.frame.midY)), current.height < element.frame.height * 3 {
                    XCUIRemote.shared.press(.select); return
                }
                let dx = element.frame.midX - current.midX
                let dy = element.frame.midY - current.midY
                if abs(dy) > 30 { XCUIRemote.shared.press(dy > 0 ? .down : .up) }
                else { XCUIRemote.shared.press(dx > 0 ? .right : .left) }
            } else { XCUIRemote.shared.press(.down) }
        }
        print(app.debugDescription)
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
