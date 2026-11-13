// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import XCTest
import UIKit

/// Run on an owned fresh simulator or cleared app sandbox. The local HTTP source
/// carries synthetic metadata, never a demo/household credential or server write.
final class PlayerPresentationUITests: XCTestCase {
    private var app: XCUIApplication!
    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
    }
    #if os(tvOS)
    @MainActor func testTVBackgroundReturnKeepsClockPausedUntilFocusedSelect() throws {
        let fixture = try NativeTrackHTTPFixture(durationSeconds: 120)
        try fixture.start()
        defer { fixture.stop(); app.terminate() }
        try fixture.launchSignedInDetail(app, activate: { self.activate($0) })
        activate(app.buttons["play"])
        let player = app.descendants(matching: .any).matching(identifier: "pictureshowNativePlayer").firstMatch
        XCTAssertTrue(player.waitForExistence(timeout: 20))
        func elapsed() -> Int? {
            let clock = app.descendants(matching: .any).matching(identifier: "AXElapsedTime").firstMatch
            guard clock.exists else { return nil }
            let values = clock.label.split(separator: ":").compactMap { Int($0) }
            guard values.count == 2 || values.count == 3 else { return nil }
            return values.reduce(0) { $0 * 60 + $1 }
        }
        XCTAssertTrue(waitForRemoteUI(timeout: 20) { (elapsed() ?? -1) >= 2 })
        let first = try XCTUnwrap(elapsed())
        XCTAssertTrue(waitForRemoteUI(timeout: 10) { (elapsed() ?? -1) >= first + 2 }, "The fixture must actually decode before lifecycle input")
        let initialFocus = try focusedSnapshot(timeout: 5)
        record("132 TV playing surface before Home")
        XCTAssertGreaterThan(initialFocus.frame.height, app.frame.height * 0.75, "This acceptance path begins with input focus on the playback surface")
        let homeAt = ProcessInfo.processInfo.systemUptime
        XCUIRemote.shared.press(.home)
        Thread.sleep(forTimeInterval: 2)
        app.activate()
        XCTAssertTrue(player.waitForExistence(timeout: 15))
        XCTAssertTrue(waitForRemoteUI(timeout: 10) {
            fixture.snapshot.requests.last(where: { $0.path == "/Sessions/Playing/Progress" && $0.observedAt >= homeAt })?.paused == true
        }, "The ordinary background progress receipt must be paused")
        let returned = try XCTUnwrap(elapsed())
        Thread.sleep(forTimeInterval: 3)
        let later = try XCTUnwrap(elapsed())
        let returnedFocus = try focusedSnapshot(timeout: 5)
        print("LEG132_TV_BACKGROUND returned=\(returned) later=\(later) focusIdentifier=\(returnedFocus.identifier) focusLabel=\(returnedFocus.label) focusFrame=\(returnedFocus.frame)")
        record("132 TV foreground paused focused Play surface")
        XCTAssertLessThanOrEqual(abs(later - returned), 1, "Foregrounding must not clear the background pause intent")
        XCTAssertEqual(returnedFocus.identifier, "foregroundPlay", "A return from the native playback surface must focus the explicit Play action")
        XCUIRemote.shared.press(.home)
        Thread.sleep(forTimeInterval: 2)
        app.activate()
        let repeatedPlay = app.buttons["foregroundPlay"]
        XCTAssertTrue(repeatedPlay.waitForExistence(timeout: 15))
        let repeatedFocus = try focusedSnapshot(timeout: 5)
        record("132 TV repeated foreground return retains Play focus")
        XCTAssertEqual(repeatedFocus.identifier, "foregroundPlay", "Home from focused Play must return to the same deliberate Play action")
        XCTAssertLessThanOrEqual(abs((elapsed() ?? -100) - later), 1, "Repeated foregrounding must remain paused")
        let selectedPlayAt = ProcessInfo.processInfo.systemUptime
        XCUIRemote.shared.press(.select)
        // AVKit retains a stale AXElapsedTime while its HUD is hidden. The
        // ordinary progress receipt reads the actual AVPlayer currentTicks.
        let resumed = waitForRemoteUI(timeout: 12) {
            fixture.snapshot.requests.contains {
                $0.path == "/Sessions/Playing/Progress" && $0.observedAt >= selectedPlayAt &&
                $0.paused == false && ($0.positionTicks ?? 0) >= Int64(later + 2) * 10_000_000
            }
        }
        // Reveal only to inspect the already-resumed native clock. This sends
        // directional input, never another Select or Play/Pause command.
        if resumed { revealNativeTransportIfHidden() }
        let visibleClockAdvanced = resumed && waitForRemoteUI(timeout: 5) { (elapsed() ?? -1) >= later + 2 }
        record("132 TV one focused Select resumes after foreground")
        XCTAssertTrue(resumed, "One Select on focused Play must advance the actual decoder timeline; no Play/Pause fallback is accepted")
        XCTAssertTrue(visibleClockAdvanced, "The revealed native HUD must agree with the decoder progress receipt")
        XCTAssertEqual(fixture.snapshot.startCount, 1, "Foreground Play retains the same viewing")
        XCTAssertEqual(fixture.snapshot.stoppedCount, 0)
        print("LEG132_TV_BACKGROUND playingBeforeHome=true returnPaused=true explicitPlayFocused=true oneSelectResumes=true")
        // An open control owns its own focus across the same lifecycle. The
        // foreground Play handoff belongs only to the playback surface.
        activate(nativeAction(id: "playerSpeed", prefix: "Speed:"))
        let selected = speedChoice("1×")
        XCTAssertTrue(selected.waitForExistence(timeout: 10))
        let panelFocus = try focusedSnapshot(timeout: 5)
        record("132 TV Speed focus before background")
        XCTAssertEqual(panelFocus.label, "1×")
        XCTAssertTrue(panelFocus.frame.contains(CGPoint(x: selected.frame.midX, y: selected.frame.midY)), "The native focused row must contain the selected rate button")
        XCUIRemote.shared.press(.home)
        Thread.sleep(forTimeInterval: 2)
        app.activate()
        XCTAssertTrue(selected.waitForExistence(timeout: 15))
        let retainedFocus = try focusedSnapshot(timeout: 5)
        record("132 TV other control retains foreground focus")
        XCTAssertFalse(app.buttons["foregroundPlay"].exists, "Foreground cannot add Play over another control's retained focus")
        XCTAssertEqual(retainedFocus.label, panelFocus.label, "The selected Speed control keeps its own focus")
        XCTAssertTrue(retainedFocus.frame.contains(CGPoint(x: selected.frame.midX, y: selected.frame.midY)), "Foreground focus remains on the exact selected rate row")
        print("LEG132_TV_BACKGROUND otherControlFocusRetained=true noPlayFocusPullback=true")
    }
    #endif

    #if os(tvOS)
    @MainActor func testNativePlaybackTabShowsConversionReasonsAndReceivedFormats() throws {
        let fixture = try NativeTrackHTTPFixture(durationSeconds: 120,
            conversionReasons: ["VideoCodecNotSupported", "ContainerBitrateExceedsLimit", "unrecognized-private-reason"])
        try fixture.start()
        defer { fixture.stop(); app.terminate() }
        try fixture.launchSignedInDetail(app, activate: { self.activate($0) })
        activate(app.buttons["play"])
        let player = app.descendants(matching: .any).matching(identifier: "pictureshowNativePlayer").firstMatch
        XCTAssertTrue(player.waitForExistence(timeout: 20))
        let notice = app.descendants(matching: .any).matching(identifier: "conversionNotice").firstMatch
        XCTAssertTrue(notice.waitForExistence(timeout: 18))
        XCTAssertTrue(notice.label.contains("choose the Playback tab"), "The visible notice must name the actual native destination")
        record("132 TV conversion notice names native Playback tab")
        let initialNegotiations = fixture.snapshot.playbackInfoCount
        XCUIRemote.shared.press(.playPause)
        revealNativeTransportIfHidden()
        let identifiedTab = app.cells["Playback"]
        let playbackTab = identifiedTab.exists ? identifiedTab : app.cells.containing(.staticText, identifier: "Playback").firstMatch
        XCTAssertTrue(playbackTab.waitForExistence(timeout: 10), "AVKit must expose the actual Playback sibling of Info and Chapters")
        XCTAssertFalse(app.buttons["playerOptionsDone"].exists, "Begin outside the separate Playback info modal")
        // The native transport, scrubber, and lower tab row are separate
        // focus environments. Cross them with Down before horizontal routing;
        // the general transport helper intentionally routes a surface Up.
        activate(nativeAction(id: "playerSpeed", prefix: "Speed:"), select: false)
        let tabRow = playbackTab.frame
        XCTAssertFalse(tabRow.isEmpty)
        var reachedTabRow = false
        for step in 1...2 {
            XCUIRemote.shared.press(.down)
            let focused = try focusedSnapshot(timeout: 1)
            print("LEG132_NATIVE_TAB_DOWN step=\(step) focus=\(focused.frame) label=\(focused.label)")
            record("132 TV native Playback route Down \(step)")
            if focused.frame.height < tabRow.height * 3,
               abs(focused.frame.midY - tabRow.midY) < tabRow.height {
                reachedTabRow = true
                break
            }
        }
        XCTAssertTrue(reachedTabRow, "Two native Down inputs must reach the actual Info/Playback tab row through the scrubber")
        guard reachedTabRow else { return }
        activate(playbackTab)
        let reason = app.descendants(matching: .any).matching(identifier: "conversionReason-0").firstMatch
        XCTAssertTrue(reason.waitForExistence(timeout: 10))
        XCTAssertTrue(reason.isHittable, "The native Playback tab must visibly present its conversion explanation")
        XCTAssertTrue(reason.label.contains("cannot decode this video format"))
        XCTAssertFalse(app.buttons["playerOptionsDone"].exists, "Native Playback must not silently route through the separate modal")
        record("132 TV native Playback tab conversion reasons")
        let server = app.descendants(matching: .any).matching(identifier: "playbackInfo-Server-reported source-Video codec").firstMatch
        reveal(server)
        XCTAssertTrue(server.exists && server.isHittable)
        XCTAssertTrue(server.label.contains("H.264"))
        record("132 TV native Playback tab server formats")
        let received = app.descendants(matching: .any).matching(identifier: "playbackInfo-Received by player-Video codec").firstMatch
        reveal(received)
        XCTAssertTrue(received.exists && received.isHittable, "Received formats must remain visibly reachable through native tab overflow")
        XCTAssertTrue(received.label.contains("H.264"))
        let info = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@ OR identifier BEGINSWITH %@", "playbackInfo-", "conversionReason-")).allElementsBoundByIndex.map(\.label).joined(separator: " ")
        for privateText in ["unrecognized-private-reason", "synthetic-native-fixture-token", "/Videos/", "http://"] {
            XCTAssertFalse(info.contains(privateText))
        }
        record("132 TV native Playback tab received formats scrolled")
        XCTAssertEqual(fixture.snapshot.playbackInfoCount, initialNegotiations, "Native information must not renegotiate the viewing")
        XCTAssertEqual(fixture.snapshot.startCount, 1, "Native information retains the same viewing")
        XCTAssertEqual(fixture.snapshot.stoppedCount, 0)
    }
    #endif

    @MainActor func testPlayerSpeedInfoConversionAndAdaptiveControls() throws {
        #if os(tvOS)
        let fixtureCaption = "At the end of this synthetic episode, the final conversation continues.\nThe following episode controls must leave both subtitle lines readable."
        #else
        let fixtureCaption = "Synthetic native menu fixture caption."
        #endif
        if ProcessInfo.processInfo.environment["PICTURESHOW_PLAYER_LARGE_FONT"] == "1" {
            // This per-app UIKit launch projection is independent of whether
            // the simulator exposes a system-wide content_size command.
            app.launchArguments += ["-UIPreferredContentSizeCategoryName", UIContentSizeCategory.extraExtraExtraLarge.rawValue]
        }
        let fixture = try NativeTrackHTTPFixture(durationSeconds: 120,
            conversionReasons: ["VideoCodecNotSupported", "ContainerBitrateExceedsLimit", "unrecognized-private-reason"], episodeContinuation: true, captionText: fixtureCaption)
        try fixture.start()
        defer { fixture.stop(); app.terminate() }
        try fixture.launchSignedInDetail(app, activate: { self.activate($0) })
        activate(app.buttons["play"])
        let player = app.descendants(matching: .any).matching(identifier: "pictureshowNativePlayer").firstMatch
        XCTAssertTrue(player.waitForExistence(timeout: 20))
        let notice = app.descendants(matching: .any).matching(identifier: "conversionNotice").firstMatch
        XCTAssertTrue(notice.waitForExistence(timeout: 18), "Negotiated device video conversion must show a notice")
        let noticeFirstObserved = Date()
        let noticeLabel = notice.label
        XCTAssertTrue(noticeLabel.contains("1080p H.264 copy"), "The visible notice must include the core fix sentence")
        #if os(iOS)
        let isTablet = UIDevice.current.userInterfaceIdiom == .pad
        // The iPad fixture has enough video height for both the real notice
        // and its full-size caption. A short phone portrait band may defer it.
        let verifyPortraitExpiry = isTablet
        var transportBeforeNoticeExpiry: [String: CGRect] = [:]
        if verifyPortraitExpiry {
            let pause = app.buttons["playerPlayPause"]
            XCTAssertEqual(pause.label, "Pause")
            activate(pause)
            XCTAssertEqual(pause.label, "Play", "Pause before taking the notice stability baseline")
            XCTAssertTrue(notice.exists, "The paused stability baseline must observe the real notice before expiry")
            transportBeforeNoticeExpiry = try portraitTransportFrames()
        }
        let visibleCaption = app.staticTexts["playerSubtitle"]
        if visibleCaption.exists {
            let noticeFrame = notice.frame, captionFrame = visibleCaption.frame
            print("LEG132_NOTICE_CAPTION notice=\(noticeFrame) caption=\(captionFrame)")
            XCTAssertFalse(noticeFrame.intersects(captionFrame), "Conversion notice must leave the actual external subtitle visible")
            XCTAssertGreaterThan(captionFrame.height, 20, "A notice must not squash the preferred caption into a sliver")
        }
        #endif
        record("132 conversion notice and fix")
        let initialNegotiations = fixture.snapshot.playbackInfoCount
        #if os(tvOS)
        XCUIRemote.shared.press(.playPause)
        let speed = nativeAction(id: "playerSpeed", prefix: "Speed:")
        activate(speed)
        #else
        let playPause = app.buttons["playerPlayPause"]
        if !playPause.waitForExistence(timeout: 1) {
            let surface = app.descendants(matching: .any).matching(identifier: "playerSurface").firstMatch
            XCTAssertTrue(surface.exists)
            // The notice occupies the top anchor. Reveal controls through the
            // exposed lower video surface, rather than tapping that note's action.
            surface.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.75)).tap()
        }
        XCTAssertTrue(playPause.waitForExistence(timeout: 10))
        if playPause.label == "Pause" { activate(playPause) }
        XCTAssertEqual(playPause.label, "Play", "The test must pin the same viewing paused before inspecting sheets")
        if verifyPortraitExpiry {
            XCTAssertTrue(notice.exists, "The real notice must still be visible while paused before observing its expiry")
            print("LEG132_NOTICE_EXPIRY pausedNoticePresent=true observedElapsed=\(Date().timeIntervalSince(noticeFirstObserved))")
            XCTAssertTrue(waitForRemoteUI(timeout: 10) { !notice.exists }, "The original notice must actually expire, including while paused")
            let after = try portraitTransportFrames()
            record("132 portrait notice expired transport stays anchored")
            for (identifier, before) in transportBeforeNoticeExpiry {
                let frame = try XCTUnwrap(after[identifier])
                print("LEG132_NOTICE_EXPIRY_CONTROL id=\(identifier) before=\(before) after=\(frame)")
                XCTAssertEqual(frame.minX, before.minX, accuracy: 1, "Notice expiry must not move \(identifier)")
                XCTAssertEqual(frame.minY, before.minY, accuracy: 1, "Notice expiry must not move \(identifier)")
                XCTAssertEqual(frame.width, before.width, accuracy: 1)
                XCTAssertEqual(frame.height, before.height, accuracy: 1)
            }
        }
        XCTAssertEqual(app.buttons["rotatePlayer"].exists, !isTablet, "Full-size iPad follows device rotation and has no phone toggle")
        let routePicker = app.descendants(matching: .any).matching(identifier: "playerRoutePicker").firstMatch
        XCTAssertTrue(routePicker.exists, "The touch transport exposes its native Play on route control without opening household routes")
        assertPhoneOrientation(landscape: true, landmark: "initial player")
        record("132 touch player controls initial landscape")
        activate(app.buttons["playerSpeed"])
        #endif
        let current = speedChoice("1×")
        XCTAssertTrue(current.waitForExistence(timeout: 10))
        assertSelectedRate(current)
        #if os(tvOS)
        let focused = try focusedSnapshot(timeout: 5)
        XCTAssertTrue(focused.identifier == "speed-1×" || focused.label.contains("1×"), "Speed entry must focus the selected rate before any directional input")
        #endif
        #if os(iOS)
        if UIDevice.current.userInterfaceIdiom == .phone {
            let segments = app.segmentedControls["playbackSpeedSegments"]
            XCTAssertTrue(segments.waitForExistence(timeout: 5), "Compact Speed uses one native segmented row")
            for label in ["0.5×", "0.75×", "1×", "1.25×", "1.5×", "1.75×", "2×"] {
                let choice = speedChoice(label)
                XCTAssertTrue(choice.exists && choice.isHittable, "Every speed is visible without horizontal scrolling: \(label)")
                XCTAssertGreaterThanOrEqual(choice.frame.minX, segments.frame.minX - 1)
                XCTAssertLessThanOrEqual(choice.frame.maxX, segments.frame.maxX + 1)
            }
        }
        #endif
        record("132 speed selected 1x")
        activate(speedChoice("1.5×"))
        XCTAssertTrue(current.waitForNonExistence(timeout: 10), "Applying a rate closes the sheet once")
        #if os(tvOS)
        let changedSpeed = nativeAction(id: "playerSpeed", prefix: "Speed:")
        let publishedRate = waitForRemoteUI(timeout: 5) { changedSpeed.label.contains("1.5×") }
        print("LEG132_TV_SPEED_AFTER_SELECT transportLabel=\(changedSpeed.label) publishedSelectedRate=\(publishedRate)")
        record("132 TV selected rate transport before reopen")
        // Reopen before asserting the transport label, so a native cached title
        // can be distinguished from a rejected rate in the viewing itself.
        activate(changedSpeed)
        #else
        let updated = waitForRemoteUI(timeout: 5) { self.app.buttons["playerSpeed"].label.contains("1.5×") }
        print("LEG132_SPEED_AFTER_SEGMENT label=\(app.buttons["playerSpeed"].label)")
        record("132 native segment selection returned transport")
        XCTAssertTrue(updated, "The current viewing rate must reach the mounted transport after selecting a segment")
        activate(app.buttons["playerSpeed"])
        #endif
        let selected = speedChoice("1.5×")
        XCTAssertTrue(selected.waitForExistence(timeout: 10))
        assertSelectedRate(selected)
        record("132 speed retained 1.5x")
        #if os(tvOS)
        XCTAssertTrue(publishedRate, "The live transport must publish the selected viewing rate; reopening above independently verifies the retained viewing rate")
        #endif
        activate(app.buttons["playerOptionsDone"])
        #if os(tvOS)
        activate(nativeAction(id: "playerInfo", prefix: "Playback info"))
        #else
        activate(app.buttons["playerInfo"])
        #endif
        let reason = app.descendants(matching: .any).matching(identifier: "conversionReason-0").firstMatch
        XCTAssertTrue(reason.waitForExistence(timeout: 10))
        XCTAssertTrue(reason.label.contains("cannot decode this video format"))
        record("132 playback info reasons before formats")
        let server = app.descendants(matching: .any).matching(identifier: "playbackInfo-Server-reported source-Video codec").firstMatch
        reveal(server)
        XCTAssertTrue(server.exists)
        XCTAssertTrue(server.label.contains("H.264"), "This section uses server metadata, even when the conversion candidates happen to match")
        let received = app.descendants(matching: .any).matching(identifier: "playbackInfo-Received by player-Video codec").firstMatch
        reveal(received)
        XCTAssertTrue(received.exists, "The received-format section must remain reachable beyond overflow")
        XCTAssertTrue(received.label.contains("H.264"), "The actual received codec uses the same readable name as server metadata")
        let info = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@ OR identifier BEGINSWITH %@", "playbackInfo-", "conversionReason-")).allElementsBoundByIndex.map(\.label).joined(separator: " ")
        for privateText in ["unrecognized-private-reason", "synthetic-native-fixture-token", "/Videos/", "http://"] {
            XCTAssertFalse(info.contains(privateText), "The information panel must not reveal raw unknown reasons, URLs or credentials")
        }
        record("132 playback info received formats scrolled")
        activate(app.buttons["playerOptionsDone"])
        XCTAssertEqual(fixture.snapshot.stoppedCount, 0, "Opening and closing sheets must not close the viewing")
        try verifyNextEpisodeCard(fixture)
        #if os(tvOS)
        activate(nativeAction(id: "closePlayer", prefix: "Back to details"))
        #else
        if app.buttons["rotatePlayer"].exists {
            activate(app.buttons["rotatePlayer"])
            assertPhoneOrientation(landscape: false, landmark: "after Rotate")
            record("132 iPhone portrait player controls")
        }
        activate(app.buttons["closePlayer"])
        #endif
        XCTAssertTrue(app.buttons["play"].waitForExistence(timeout: 15))
        XCTAssertEqual(fixture.snapshot.startCount, 1, "Speed and info must preserve the original viewing")
        XCTAssertEqual(fixture.snapshot.playbackInfoCount, initialNegotiations, "Changing speed and opening information must not renegotiate")
        #if os(tvOS)
        let returnedPlayAXFocus = waitForRemoteUI(timeout: 5) { self.app.buttons["play"].hasFocus }
        record("132 stopped viewing retained detail focus")
        func mutationCount() -> Int {
            fixture.snapshot.requests.filter {
                ($0.path.hasPrefix("/UserPlayedItems/") || $0.path.hasPrefix("/UserFavoriteItems/")) &&
                ($0.method == "POST" || $0.method == "DELETE")
            }.count
        }
        let mutationsBeforeSelect = mutationCount()
        // Native PrimitiveButtonStyle can highlight the inner Play control while
        // its outer AX button omits Focused. Prove its actual input route with
        // one physical Select, without directing focus through the test helper.
        let returnedDetailSelectAt = ProcessInfo.processInfo.systemUptime
        XCUIRemote.shared.press(.select)
        let selectedPlayOpened = player.waitForExistence(timeout: 15) && waitForRemoteUI(timeout: 15) { fixture.snapshot.startCount == 2 }
        record("132 returned detail one Select opens retained Play")
        XCTAssertTrue(selectedPlayOpened, "One Select from the returned detail must open its retained Play action")
        let selectedTitleRequests = fixture.snapshot.requests.filter { $0.observedAt >= returnedDetailSelectAt && $0.path.hasSuffix("/PlaybackInfo") }
        XCTAssertFalse(selectedTitleRequests.isEmpty, "The focused Play input must negotiate its title")
        XCTAssertTrue(selectedTitleRequests.allSatisfy { $0.path == "/Items/" + NativeTrackHTTPFixture.itemID + "/PlaybackInfo" }, "Every negotiation after Select must target the exact same title")
        XCTAssertEqual(mutationCount(), mutationsBeforeSelect, "Select cannot activate a watched or favorite action")
        XCTAssertEqual(fixture.snapshot.remotePlayCommands.count, 0, "The return cannot dispatch a TV chooser command")
        print("LEG132_TV_STOP_RETURN outerAXFocused=\(returnedPlayAXFocus) onePhysicalSelectStartsSameTitle=true noMutation=true")
        activate(nativeAction(id: "closePlayer", prefix: "Back to details"))
        XCTAssertTrue(app.buttons["play"].waitForExistence(timeout: 15))
        // The retained native Play primitive omits Focused in AX, as proven by
        // the exact-title single-Select probe above. Move across its two actual
        // siblings with the remote instead of estimating from the root frame.
        XCUIRemote.shared.press(.right)
        XCUIRemote.shared.press(.right)
        XCUIRemote.shared.press(.select)
        XCTAssertTrue(app.descendants(matching: .any).matching(identifier: "remote-mode-play-now").firstMatch.waitForExistence(timeout: 5), "Two physical Right inputs from retained Play must reach the native Play on TV menu")
        #else
        activate(app.buttons["playOnDevice"])
        #endif
        activate(app.descendants(matching: .any).matching(identifier: "remote-mode-play-now").firstMatch)
        let remoteStatus = app.descendants(matching: .any).matching(identifier: "remote-play-status").firstMatch
        XCTAssertTrue(remoteStatus.waitForExistence(timeout: 10), "The actual detail action must mount RemotePlayView")
        #if os(tvOS)
        XCTAssertEqual(app.staticTexts["remote-play-heading"].label, "Play on TV", "The mode is a readable in-content heading rather than a truncated navigation title")
        #endif
        let emptyDevices = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "No available TVs.")).firstMatch
        XCTAssertTrue(emptyDevices.waitForExistence(timeout: 10), "The ordinary empty Sessions response must produce an honest, recoverable chooser")
        XCTAssertTrue(app.buttons["remote-play-refresh"].isEnabled)
        let thisDevice = app.buttons["remote-play-this-device"]
        XCTAssertTrue(thisDevice.exists, "The chooser keeps an explicit local-device destination alongside its honest empty-TV state")
        record("132 Play on TV empty device chooser")
        XCTAssertGreaterThanOrEqual(fixture.snapshot.requests.filter { $0.method == "GET" && $0.path == "/Sessions" }.count, 1)
        activate(app.buttons["remote-play-done"])
        XCTAssertTrue(app.buttons["playOnDevice"].waitForExistence(timeout: 10))
        #if os(tvOS)
        let returnedTVMenuAXFocus = waitForRemoteUI(timeout: 5) { self.app.buttons["playOnDevice"].hasFocus }
        record("132 closed chooser retained TV menu opener")
        XCUIRemote.shared.press(.select)
        XCTAssertTrue(app.descendants(matching: .any).matching(identifier: "remote-mode-play-now").firstMatch.waitForExistence(timeout: 5), "One Select after closing the chooser must reopen its actual detail menu opener")
        print("LEG132_TV_CHOOSER_RETURN outerAXFocused=\(returnedTVMenuAXFocus) onePhysicalSelectOpensRetainedTVMenu=true")
        XCUIRemote.shared.press(.menu)
        #endif
        print("LEG132_REMOTE_UI detailAction=true chooserMounted=true emptyTargetsHonest=true refreshEnabled=true")
        print("LEG132_PLAYER_UI speedSelected=true rateRetained=true infoOverflowReachable=true conversionFixVisible=true singleViewing=true")
    }

    #if os(iOS)
    @MainActor func testSubtitleDelayButtonsApplyOneStepAndResetWithinSameViewing() throws {
        let fixture = try NativeTrackHTTPFixture(durationSeconds: 120, compactTracks: true)
        fixture.releaseAllMenuWorkers()
        try fixture.start()
        defer { fixture.stop(); app.terminate() }
        try fixture.launchSignedInDetail(app, activate: { self.activate($0) })
        activate(app.buttons["play"])
        let pause = app.buttons["playerPlayPause"]
        XCTAssertTrue(pause.waitForExistence(timeout: 20))
        if pause.label == "Pause" { activate(pause) }
        XCTAssertEqual(pause.label, "Play")
        if UIDevice.current.userInterfaceIdiom == .phone {
            activate(app.buttons["rotatePlayer"])
            assertPhoneOrientation(landscape: false, landmark: "subtitle timing portrait")
        }
        activate(app.buttons["playerTracks"])
        let later = app.buttons["subtitleLater"]
        reveal(later)
        XCTAssertTrue(later.waitForExistence(timeout: 10)); XCTAssertTrue(later.isEnabled)
        let value = app.staticTexts["subtitleDelayValue"]
        XCTAssertEqual(value.label, "Subtitle delay: +0.0 s")
        for id in ["subtitleEarlier", "subtitleReset", "subtitleLater"] {
            let frame = app.buttons[id].frame
            XCTAssertGreaterThanOrEqual(frame.height + 0.000_001, 48)
            XCTAssertLessThanOrEqual(frame.height, 56, "Native timing buttons must not add border padding around a 48pt label")
            XCTAssertGreaterThanOrEqual(frame.width, 48)
            XCTAssertEqual(frame.width, later.frame.width, accuracy: 1)
        }
        XCTAssertFalse(app.buttons["subtitleReset"].isEnabled)
        activate(later)
        XCTAssertEqual(value.label, "Subtitle delay: +0.1 s", "A Later tap commits one 100 ms step")
        record("132 subtitle timing later one step paused")
        activate(app.buttons["subtitleEarlier"])
        XCTAssertEqual(value.label, "Subtitle delay: +0.0 s", "Earlier must not trigger other buttons sharing the List row")
        activate(later)
        activate(app.buttons["subtitleReset"])
        XCTAssertEqual(value.label, "Subtitle delay: +0.0 s")
        XCTAssertFalse(app.buttons["subtitleReset"].isEnabled)
        record("132 subtitle timing reset and selected text track")
        activate(app.buttons["playerOptionsDone"])
        XCTAssertEqual(pause.label, "Play", "Subtitle timing preserves pause intent")
        XCTAssertEqual(fixture.snapshot.startCount, 1); XCTAssertEqual(fixture.snapshot.stoppedCount, 0)
        activate(app.buttons["closePlayer"])
        XCTAssertTrue(app.buttons["play"].waitForExistence(timeout: 15))
    }
    #endif

    @MainActor func testRemotePlayNowPendingWindowTimesOutWithoutResending() throws {
        let fixture = try NativeTrackHTTPFixture(durationSeconds: 120, remoteTarget: true)
        try fixture.start()
        defer { fixture.stop(); app.terminate() }
        try fixture.launchSignedInDetail(app, activate: { self.activate($0) })
        activate(app.buttons["playOnDevice"])
        activate(app.descendants(matching: .any).matching(identifier: "remote-mode-play-now").firstMatch)
        let status = app.descendants(matching: .any).matching(identifier: "remote-play-status").firstMatch
        let target = app.buttons["remote-play-target-" + NativeTrackHTTPFixture.remoteSessionID]
        XCTAssertTrue(target.waitForExistence(timeout: 10), "The ordinary Sessions response must populate the actual device chooser")
        #if os(tvOS)
        XCTAssertEqual(app.staticTexts["remote-play-heading"].label, "Play on TV")
        #endif
        XCTAssertTrue(target.label.contains("Synthetic fixture TV"))
        XCTAssertTrue(target.label.contains("Previously playing synthetic title"), "The chooser must expose the observed current title before sending")
        record("132 Play on TV populated synthetic device chooser")
        activate(target)
        XCTAssertTrue(waitForRemoteUI(timeout: 5) { fixture.snapshot.remotePlayCommands.count == 1 })
        let command = try XCTUnwrap(fixture.snapshot.remotePlayCommands.first)
        XCTAssertEqual(command.playCommand, "PlayNow")
        XCTAssertEqual(command.itemIDs, NativeTrackHTTPFixture.remoteItemID)
        XCTAssertTrue(waitForRemoteUI(timeout: 5) { status.exists && status.label == "Waiting for the TV to start this title…" }, "An HTTP 204 and the old title must not claim playback confirmation")
        record("132 Play Now accepted waiting for requested title")

        // Observe the real mounted UI beyond the old five-second deadline.
        // Hosted virtual-clock tests own the exact 11,999/12,000 ms boundary.
        XCTAssertTrue(waitForRemoteUI(timeout: 9) { ProcessInfo.processInfo.systemUptime >= command.observedAt + 8.5 })
        XCTAssertEqual(status.label, "Waiting for the TV to start this title…", "Play Now must remain pending beyond five seconds while the old title is still observed")
        XCTAssertEqual(fixture.snapshot.remotePlayCommands.count, 1)
        XCTAssertGreaterThan(fixture.snapshot.remoteSessionReadCount, 2, "Pending status must observe ordinary Sessions responses")
        record("132 Play Now still pending beyond five seconds")
        XCTAssertTrue(waitForRemoteUI(timeout: 6) { status.label.contains("Playback could not be confirmed.") }, "The real confirmation window must eventually reach the honest unknown state")
        let timeoutElapsed = ProcessInfo.processInfo.systemUptime - command.observedAt
        XCTAssertGreaterThanOrEqual(timeoutElapsed, 11, "The mounted UI cannot fall back to the old five-second window")
        XCTAssertLessThan(timeoutElapsed, 18, "The mounted UI must finish its twelve-second confirmation window promptly")
        XCTAssertTrue(status.label.contains("Refreshing will not send the title again."))
        record("132 Play Now twelve second window unknown no automatic resend")

        fixture.observeRequestedRemoteTitle()
        let reads = fixture.snapshot.remoteSessionReadCount
        let refresh = app.buttons["remote-play-refresh"]
        XCTAssertTrue(waitForRemoteUI(timeout: 5) { refresh.isEnabled })
        activate(refresh)
        XCTAssertTrue(waitForRemoteUI(timeout: 5) { fixture.snapshot.remoteSessionReadCount > reads && refresh.isEnabled })
        XCTAssertTrue(status.label.contains("Playback could not be confirmed."), "A late observation cannot revive the expired command")
        XCTAssertEqual(fixture.snapshot.remotePlayCommands.count, 1, "Explicit Refresh observes status and must never replay an uncertain Play Now")
        record("132 Play Now refresh after timeout remains unknown without resend")
        activate(app.buttons["remote-play-done"])
        #if os(tvOS)
        XCTAssertTrue(app.buttons["playOnDevice"].waitForExistence(timeout: 10))
        record("132 populated chooser closed retained TV menu opener")
        XCUIRemote.shared.press(.select)
        XCTAssertTrue(app.descendants(matching: .any).matching(identifier: "remote-mode-play-now").firstMatch.waitForExistence(timeout: 5), "A single physical Select after chooser dismissal must reopen its retained TV menu")
        #else
        activate(app.buttons["playOnDevice"])
        #endif
        activate(app.descendants(matching: .any).matching(identifier: "remote-mode-play-now").firstMatch)
        XCTAssertTrue(target.waitForExistence(timeout: 10))
        XCTAssertTrue(target.label.contains("Now playing: Synthetic native track menu film"), "A fresh chooser must display the actual requested title from the new observation")
        XCTAssertEqual(fixture.snapshot.remotePlayCommands.count, 1, "Reopening a chooser observes the attached TV without dispatching")
        record("132 attached synthetic TV observed requested title after timeout")
        activate(app.buttons["remote-play-done"])
        XCTAssertTrue(app.buttons["playOnDevice"].waitForExistence(timeout: 10))
        XCTAssertEqual(fixture.snapshot.startCount, 0, "Choosing the synthetic TV must never start a local viewing")
        print("LEG132_REMOTE_PENDING_UI pendingAfter8_5seconds=true timeoutObservedAfter=\(timeoutElapsed) playNowCommands=\(fixture.snapshot.remotePlayCommands.count) refreshNoResend=true expiredNotRevived=true reopenedObservedRequestedTitle=true")
    }

    @MainActor private func waitForRemoteUI(timeout: TimeInterval, _ predicate: @escaping () -> Bool) -> Bool {
        let expectation = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in predicate() }, object: nil)
        return XCTWaiter.wait(for: [expectation], timeout: timeout) == .completed
    }

    @MainActor private func verifyNextEpisodeCard(_ fixture: NativeTrackHTTPFixture) throws {
        #if os(tvOS)
        activate(nativeAction(id: "playerSpeed", prefix: "Speed:"))
        #else
        activate(app.buttons["playerSpeed"])
        #endif
        activate(speedChoice("0.5×"))
        #if os(iOS)
        let phone = UIDevice.current.userInterfaceIdiom == .phone
        if phone {
            activate(app.buttons["rotatePlayer"])
            assertPhoneOrientation(landscape: false, landmark: "portrait speed and chapters")
            activate(app.buttons["playerSpeed"])
            XCTAssertEqual(speedChoice("0.5×").value as? String, "Selected")
            record("132 iPhone portrait speed selected half speed")
            activate(app.buttons["playerOptionsDone"])
            activate(app.buttons["playerInfo"])
            record("132 iPhone portrait playback info reasons")
            let received = app.staticTexts["playbackInfo-Received by player-Video codec"]
            reveal(received)
            XCTAssertTrue(received.exists)
            record("132 iPhone portrait playback info received formats")
            activate(app.buttons["playerOptionsDone"])
        }
        #endif
        #if os(tvOS)
        activate(nativeAction(id: "chapters", prefix: "Chapters"))
        let nearEnd = app.cells.matching(NSPredicate(format: "label BEGINSWITH %@", "Near end")).firstMatch
        #else
        activate(app.buttons["playerChapters"])
        let nearEnd = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Near end")).firstMatch
        #endif
        XCTAssertTrue(nearEnd.waitForExistence(timeout: 10), "The fixture chapter must be supplied through production metadata")
        record("132 chapters near-end navigation")
        #if os(tvOS)
        // AVKit's transient two-row popover enters on Opening. Use the actual
        // remote route to its next row before idle dismissal, without multiple
        // expensive AX snapshots of a transient native collection.
        XCUIRemote.shared.press(.down)
        XCUIRemote.shared.press(.select)
        #else
        activate(nearEnd)
        #endif
        #if os(tvOS)
        let playNext = app.cells.matching(NSPredicate(format: "identifier == %@ OR label BEGINSWITH %@", "playNextTransport", "Play next: Following synthetic episode")).firstMatch
        XCTAssertTrue(playNext.waitForExistence(timeout: 10))
        XCTAssertFalse(playNext.label.contains("("), "Paused continuation keeps the named Play next action but has no countdown number")
        activate(playNext, select: false)
        record("132 paused next episode Play next focused label")
        let cancelNextTransport = nativeAction(id: "cancelNextTransport", prefix: "Cancel")
        activate(cancelNextTransport, select: false)
        record("132 paused next episode Cancel focused label")
        #else
        let playNext = app.buttons["playNext"]
        XCTAssertTrue(playNext.waitForExistence(timeout: 10))
        XCTAssertFalse(app.staticTexts["nextCountdown"].exists, "Paused continuation keeps the named Play next action but has no countdown number")
        XCTAssertTrue(app.buttons["cancelNext"].exists)
        try assertNextCardGeometry(landmark: "paused portrait or tablet")
        if phone {
            record("132 iPhone portrait next episode paused manual card")
            activate(app.buttons["rotatePlayer"])
            assertPhoneOrientation(landscape: true, landmark: "landscape next episode")
            try assertNextCardGeometry(landmark: "paused landscape")
        }
        #endif
        #if os(tvOS)
        // AVKit may retain hidden transport AX nodes. Save the actual compact
        // transport geometry before idle so they cannot satisfy contextual proof.
        let continuationTransportFrames = [playNext.frame, cancelNextTransport.frame]
        #endif
        record("132 next episode paused manual card")
        #if os(iOS)
        if UIDevice.current.userInterfaceIdiom == .pad {
            XCUIDevice.shared.orientation = .landscapeLeft
            XCTAssertTrue(waitForRemoteUI(timeout: 10) { self.app.windows.firstMatch.frame.width > self.app.windows.firstMatch.frame.height })
            XCTAssertFalse(app.buttons["rotatePlayer"].exists, "iPad rotation remains device-owned")
            try assertNextCardGeometry(landmark: "iPad landscape device rotation")
            record("132 iPad landscape next episode after device rotation")
        }
        #endif
        #if os(tvOS)
        XCUIRemote.shared.press(.playPause)
        let deadline = ProcessInfo.processInfo.systemUptime + 18
        var number: Int?
        repeat {
            number = trailingNumber(playNext.label)
        } while number == nil && ProcessInfo.processInfo.systemUptime < deadline
        XCTAssertNotNil(number, "After the native transport idles away, resumed playback publishes the running countdown in the retained action")
        #else
        activate(app.buttons["playerPlayPause"])
        let countdown = app.staticTexts["nextCountdown"]
        XCTAssertTrue(countdown.waitForExistence(timeout: 6), "Resumed playback publishes its running countdown after the deliberate-seek hold")
        let number = countdown.label.split(whereSeparator: { !$0.isNumber }).first.flatMap { Int($0) }
        #endif
        let value = try XCTUnwrap(number)
        XCTAssertGreaterThan(value, 0)
        XCTAssertLessThanOrEqual(value, 10, "The credits budget remains ten wall seconds")
        #if os(tvOS)
        let nativePlayer = app.descendants(matching: .any).matching(identifier: "pictureshowNativePlayer").firstMatch
        record("132 TV running contextual state before assertions")
        XCTAssertEqual(nativePlayer.value as? String, "Controls hidden", "Running contextual proof requires the actual hidden HUD after the existing idle bound")
        var contextualPlay = try nativeContextualAction(in: nativePlayer, id: "playNext", title: "Play next: Following synthetic episode", transportFrames: continuationTransportFrames)
        var contextualCancel = try nativeContextualAction(in: nativePlayer, id: "cancelNext", title: "Cancel", transportFrames: continuationTransportFrames)
        XCTAssertTrue(contextualPlay.exists && contextualPlay.isHittable)
        XCTAssertTrue(contextualCancel.exists && contextualCancel.isHittable)
        XCTAssertTrue(contextualPlay.label.hasPrefix("Play next: Following synthetic episode"))
        XCTAssertNotNil(trailingNumber(contextualPlay.label), "The visible contextual Play next text must carry the running number")
        XCTAssertEqual(contextualCancel.label, "Cancel")
        let runningContextCancelFrame = contextualCancel.frame
        XCTAssertFalse(runningContextCancelFrame.isEmpty)
        let caption = app.staticTexts["playerSubtitle"]
        XCTAssertTrue(caption.exists)
        XCTAssertGreaterThan(caption.frame.height, 60, "The final TV acceptance cue contains two real subtitle lines")
        XCTAssertLessThanOrEqual(caption.frame.maxY, app.frame.maxY - 160, "External captions must remain above native contextual Skip/Next/Cancel even with the HUD hidden")
        #endif
        // At 115 seconds in a 120-second file, 0.5× leaves up to ten real
        // seconds. This snapshot is separate from the exact hosted clock test.
        #if os(iOS)
        let runningCancelOrigin = app.buttons["cancelNext"].frame.origin
        #endif
        record("132 next episode running at half speed")
        #if os(tvOS)
        XCUIRemote.shared.press(.playPause)
        let pausedContextSettled = waitForRemoteUI(timeout: 5) {
            self.trailingNumber(playNext.label) == nil && nativePlayer.exists && nativePlayer.value as? String == "Controls hidden"
        }
        record("132 TV paused contextual state before assertions")
        XCTAssertTrue(pausedContextSettled, "The existing single Pause must remove the number and retain the actual hidden HUD within the original pause bound")
        XCTAssertNil(trailingNumber(playNext.label), "Remote pause removes the number immediately")
        contextualPlay = try nativeContextualAction(in: nativePlayer, id: "playNext", title: "Play next: Following synthetic episode", transportFrames: continuationTransportFrames)
        contextualCancel = try nativeContextualAction(in: nativePlayer, id: "cancelNext", title: "Cancel", transportFrames: continuationTransportFrames)
        XCTAssertTrue(contextualPlay.exists && contextualPlay.isHittable)
        XCTAssertTrue(contextualCancel.exists && contextualCancel.isHittable)
        XCTAssertEqual(contextualPlay.label, "Play next: Following synthetic episode")
        XCTAssertEqual(contextualCancel.label, "Cancel", "The paused contextual pill must render the Cancel word")
        XCTAssertNil(trailingNumber(contextualPlay.label))
        XCTAssertEqual(contextualCancel.frame.minX, runningContextCancelFrame.minX, accuracy: 1, "Pausing cannot move contextual Cancel")
        XCTAssertEqual(contextualCancel.frame.minY, runningContextCancelFrame.minY, accuracy: 1, "Pausing cannot move contextual Cancel")
        XCTAssertEqual(contextualCancel.frame.width, runningContextCancelFrame.width, accuracy: 1, "Pausing cannot resize contextual Cancel")
        XCTAssertEqual(contextualCancel.frame.height, runningContextCancelFrame.height, accuracy: 1, "Pausing cannot resize contextual Cancel")
        XCTAssertEqual(fixture.snapshot.startCount, 1)
        record("132 TV HUD hidden paused contextual episode identity and Cancel text")
        let cancel = nativeAction(id: "cancelNextTransport", prefix: "Cancel")
        activate(cancel)
        #else
        activate(app.buttons["playerPlayPause"])
        XCTAssertTrue(app.staticTexts["nextCountdown"].waitForNonExistence(timeout: 5), "Touch pause removes the number immediately")
        let pausedCancelOrigin = app.buttons["cancelNext"].frame.origin
        XCTAssertEqual(pausedCancelOrigin.x, runningCancelOrigin.x, accuracy: 1, "Cancel keeps its horizontal origin across pause")
        XCTAssertEqual(pausedCancelOrigin.y, runningCancelOrigin.y, accuracy: 1, "Suppressing the number cannot move Cancel")
        record("132 next episode paused after running countdown")
        activate(app.buttons["cancelNext"])
        #endif
        XCTAssertTrue(playNext.waitForNonExistence(timeout: 10), "Cancel removes the continuation card while retaining this viewing")
        XCTAssertEqual(fixture.snapshot.startCount, 1, "The card and Cancel cannot open the next episode")
        print("LEG132_NEXT_UI rate=0.5 runningNumber=\(value) pausedNumberHidden=true cancelRemoved=true chronologicalNext=true")
    }
    private func trailingNumber(_ label: String) -> Int? {
        guard let start = label.lastIndex(of: "("), label.last == ")" else { return nil }
        return Int(label[label.index(after: start)..<label.index(before: label.endIndex)])
    }

    @MainActor private func record(_ name: String) {
        #if os(tvOS)
        // A requested UIKit launch category is not proof that tvOS enlarged it.
        // Qualify the gallery only after comparing actual font diagnostics.
        let largePrefix = "132 requested large font "
        #else
        let largePrefix = "132 large font "
        #endif
        let name = ProcessInfo.processInfo.environment["PICTURESHOW_PLAYER_LARGE_FONT"] == "1" ? name.replacingOccurrences(of: "132 ", with: largePrefix) : name
        let text = XCTAttachment(string: app.debugDescription); text.name = name + " accessibility"; text.lifetime = .keepAlways; add(text)
        let screenshot = XCTAttachment(screenshot: XCUIScreen.main.screenshot()); screenshot.name = name; screenshot.lifetime = .keepAlways; add(screenshot)
    }
    #if os(iOS)
    @MainActor private func portraitTransportFrames() throws -> [String: CGRect] {
        // One actual AX snapshot keeps the three-frame baseline inside the
        // original ten-second notice lifetime, without freezing its clock.
        let snapshot = try app.snapshot()
        let window = snapshot.frame
        XCTAssertGreaterThan(window.height, window.width, "Notice stability is measured in the actual portrait window")
        var nodes: [XCUIElementSnapshot] = []
        func collect(_ node: XCUIElementSnapshot) { nodes.append(node); node.children.forEach(collect) }
        collect(snapshot)
        let controls: [(String, XCUIElement.ElementType)] = [
            ("playerPlayPause", .button), ("playerScrubber", .slider), ("playerSpeed", .button)
        ]
        var result: [String: CGRect] = [:]
        for (identifier, role) in controls {
            let matches = nodes.filter { $0.identifier == identifier && $0.elementType == role }
            XCTAssertEqual(matches.count, 1, "The actual \(identifier) must have one native control")
            let frame = try XCTUnwrap(matches.first).frame
            XCTAssertFalse(frame.isEmpty)
            XCTAssertTrue(window.contains(frame), "The full \(identifier) target must remain in the portrait window")
            result[identifier] = frame
        }
        return result
    }
    @MainActor private func assertNextCardGeometry(landmark: String) throws {
        let window = app.windows.firstMatch.frame
        var nodes: [XCUIElementSnapshot] = []
        func collect(_ node: XCUIElementSnapshot) { nodes.append(node); node.children.forEach(collect) }
        collect(try app.snapshot())
        let card = try XCTUnwrap(nodes.first { $0.identifier == "nextEpisodeCard" }, "The real continuation card must be mounted")
        let caption = try XCTUnwrap(nodes.first { $0.identifier == "playerSubtitle" || $0.label == "Synthetic native menu fixture caption." }, "The fixture caption must remain visible while the card is mounted")
        let controlIDs: Set<String> = ["playNext", "cancelNext", "playerTitle", "playbackPosition", "playbackMethod", "playerScrubber", "playerPlayPause", "playerRoutePicker", "closePlayer", "playerMore", "playerTracks", "playerSeek", "playerChapters", "playerSpeed", "playerInfo", "rotatePlayer", "skipSegment"]
        let controls = nodes.filter { controlIDs.contains($0.identifier) || ($0.elementType == .button && ["Back 10 seconds", "Forward 10 seconds"].contains($0.label)) }
        for id in ["playNext", "cancelNext", "playerPlayPause", "closePlayer"] {
            XCTAssertTrue(controls.contains { $0.identifier == id }, "The continuation cannot displace required transport action \(id)")
        }
        // Portrait can scroll its controls below the video at accessibility
        // sizes; landscape must fit every rendered control beside the card.
        let bounded = [card, caption] + (window.width > window.height ? controls : [])
        for node in bounded {
            XCTAssertGreaterThan(node.frame.width, 0, "\(node.identifier) must have visible width")
            XCTAssertGreaterThan(node.frame.height, 0, "\(node.identifier) must have visible height")
            XCTAssertTrue(window.insetBy(dx: -1, dy: -1).contains(node.frame), "\(landmark): \(node.identifier.isEmpty ? node.label : node.identifier) overflows the actual window: \(node.frame), window \(window)")
        }
        XCTAssertFalse(card.frame.intersects(caption.frame), "Next episode chrome must never cover the actual subtitle frame: card \(card.frame), caption \(caption.frame)")
        XCTAssertFalse(nodes.contains { $0.label == "Detect Text" || $0.label == "Liftable subject available" || $0.elementType == .textView }, "Video-frame analysis controls and OCR must not compete with custom playback controls or VoiceOver")
        print("LEG132_CARD_GEOMETRY landmark=\(landmark) window=\(window) card=\(card.frame) caption=\(caption.frame) controls=\(controls.map { "\($0.identifier):\($0.frame)" }.joined(separator: " | "))")
    }
    @MainActor private func assertPhoneOrientation(landscape: Bool, landmark: String) {
        guard UIDevice.current.userInterfaceIdiom == .phone else { return }
        let window = app.windows.firstMatch
        let settled = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            let frame = window.frame
            return landscape ? frame.width > frame.height : frame.height > frame.width
        }, object: window)
        XCTAssertEqual(XCTWaiter.wait(for: [settled], timeout: 8), .completed,
            "\(landmark) must reach the requested phone orientation before its evidence capture")
        let frame = window.frame
        XCTAssertTrue(landscape ? frame.width > frame.height : frame.height > frame.width)
        if landscape {
            let native = app.descendants(matching: .any).matching(identifier: "pictureshowNativePlayer").firstMatch.frame
            print("LEG132_NATIVE_FRAME landmark=\(landmark) native=\(native) window=\(frame)")
            record("132 touch full-window video safe controls")
            XCTAssertEqual(native.minX, frame.minX, accuracy: 1, "Video extends behind the leading cutout")
            XCTAssertEqual(native.maxX, frame.maxX, accuracy: 1, "Video extends behind the trailing cutout")
            XCTAssertEqual(native.maxY, frame.maxY, accuracy: 1, "Video extends behind the home indicator")
        }
        print("LEG132_ORIENTATION landmark=\(landmark) expected=\(landscape ? "landscape" : "portrait") observedWindow=\(frame)")
    }
    #endif

    @MainActor private func reveal(_ element: XCUIElement) {
        #if os(tvOS)
        for _ in 0..<18 {
            if element.exists && element.isHittable { return }
            XCUIRemote.shared.press(.down)
        }
        #else
        if element.exists && element.isHittable { return }
        let optionsOpen = app.buttons["playerOptionsDone"].exists
        let scroll: XCUIElement
        if optionsOpen {
            // SwiftUI List is a collection view on the supported runtime. Its
            // stable identifier selects the sheet, not Detail's underlying
            // ScrollView, which remains present in the accessibility tree.
            let collection = app.collectionViews["playerOptionsList"]
            let table = app.tables["playerOptionsList"]
            let scrollView = app.scrollViews["playerOptionsList"]
            if collection.exists { scroll = collection }
            else if table.exists { scroll = table }
            else { scroll = scrollView }
            guard scroll.waitForExistence(timeout: 3) else {
                record("132 missing player options scroll surface")
                XCTFail("The mounted player sheet must expose its actual playerOptionsList scroll surface")
                return
            }
        } else if app.scrollViews["touchPlayerControls"].exists {
            scroll = app.scrollViews["touchPlayerControls"]
        } else {
            // Sign-in and retained detail have their own visible scroll area.
            // This fallback is never used while a player sheet is mounted.
            let candidates = (app.scrollViews.allElementsBoundByIndex + app.collectionViews.allElementsBoundByIndex)
                .filter { $0.isHittable && $0.frame.intersects(app.windows.firstMatch.frame) }
            let targetID = element.exists ? element.identifier : ""
            let containingTarget = targetID.isEmpty ? [] : candidates.filter {
                $0.descendants(matching: .any).matching(identifier: targetID).firstMatch.exists
            }
            guard let visible = containingTarget.last ?? candidates.last else { return }
            scroll = visible
        }
        for step in 0..<18 {
            let window = app.windows.firstMatch.frame
            var viewport = scroll.frame.intersection(window)
            if optionsOpen {
                let done = app.buttons["playerOptionsDone"].frame
                let top = max(viewport.minY + 12, done.maxY + 12)
                viewport = CGRect(x: viewport.minX + 24, y: top,
                    width: max(1, viewport.width - 48), height: max(1, viewport.maxY - top - 20))
            } else { viewport = viewport.insetBy(dx: 20, dy: 20) }
            let exists = element.exists
            if exists, element.isHittable,
                viewport.contains(CGPoint(x: element.frame.midX, y: element.frame.midY)) { return }
            guard viewport.width > 40, viewport.height > 60 else {
                XCTFail("The actual options list has no usable visible scrolling viewport: \(viewport)")
                return
            }
            // Move toward a materialized row in short, settled drags. Holding
            // at the endpoint prevents a fling from skipping it and repeatedly
            // reversing past it on the next observation.
            let targetFrame = exists ? element.frame : nil
            let distance = targetFrame.map { $0.midY - viewport.midY }
            let backwards = (distance ?? 80) < 0
            let delta = min(distance.map { min(120, max(24, abs($0))) } ?? 80,
                viewport.height * 0.43)
            let startY = backwards ? viewport.minY + 20 : viewport.maxY - 20
            let endY = backwards ? min(viewport.maxY - 20, startY + delta) : max(viewport.minY + 20, startY - delta)
            let origin = scroll.coordinate(withNormalizedOffset: .zero)
            let x = viewport.midX - scroll.frame.minX
            let start = origin.withOffset(CGVector(dx: x, dy: startY - scroll.frame.minY))
            let end = origin.withOffset(CGVector(dx: x, dy: endY - scroll.frame.minY))
            print("LEG132_LIST_SCROLL step=\(step) target=\(exists ? element.identifier : "unmaterialized requested row") targetFrame=\(targetFrame.map(String.init(describing:)) ?? "unmaterialized") surface=\(scroll.identifier) viewport=\(viewport) delta=\(delta) direction=\(backwards ? "towardStart" : "towardEnd")")
            start.press(forDuration: 0.05, thenDragTo: end, withVelocity: .slow, thenHoldForDuration: 0.5)
        }
        record("132 player options scroll target unreachable")
        XCTFail("The actual player sheet could not reveal the requested row with bounded in-list scrolling")
        #endif
    }
    @MainActor private func speedChoice(_ label: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "identifier == %@ OR label == %@", "speed-\(label)", label)).firstMatch
    }
    @MainActor private func assertSelectedRate(_ element: XCUIElement) {
        #if os(iOS)
        XCTAssertTrue(element.isSelected || ["Selected", "1"].contains(element.value as? String ?? ""), "The native rate choice must expose selection")
        #else
        XCTAssertEqual(element.value as? String, "Selected")
        #endif
    }
    @MainActor private func activate(_ element: XCUIElement, select: Bool = true) {
        #if os(iOS)
        if !element.exists, app.buttons["playerMore"].exists {
            app.buttons["playerMore"].tap()
        }
        #endif
        #if os(iOS)
        if !element.waitForExistence(timeout: 2), !app.buttons["playerOptionsDone"].exists, app.scrollViews["touchPlayerControls"].exists {
            reveal(element)
        }
        #endif
        XCTAssertTrue(element.waitForExistence(timeout: 10))
        #if os(tvOS)
        var previousFrame: CGRect?
        var previousDirection: XCUIRemote.Button?
        for _ in 0..<40 {
            if element.elementType == .cell, element.frame.width <= 100 {
                revealNativeTransportIfHidden()
            }
            if element.hasFocus { if select { XCUIRemote.shared.press(.select) }; return }
            let target = element.frame
            if ["playerOptionsDone", "remote-play-refresh", "remote-play-done"].contains(element.identifier), target.isEmpty {
                // These final native List actions have no geometry until
                // focus scrolls toward the list's end on TV.
                XCUIRemote.shared.press(.down)
                continue
            }
            let focused = try? focusedSnapshot(timeout: 0.3)
            if let frame = focused?.frame, frame.contains(CGPoint(x: target.midX, y: target.midY)), frame.height < target.height * 3 {
                if select { XCUIRemote.shared.press(.select) }; return
            }
            let frame = focused?.frame ?? app.frame
            let dx = target.midX - frame.midX, dy = target.midY - frame.midY
            let overlapsX = frame.minX < target.maxX && target.minX < frame.maxX
            let vertical = dy > 0 ? XCUIRemote.Button.down : .up
            let horizontal = dx > 0 ? XCUIRemote.Button.right : .left
            var direction = abs(dy) > 35 && (overlapsX || abs(dy) > abs(dx)) ? vertical : horizontal
            if frame.height > app.frame.height * 0.75 { direction = .up }
            // AVKit's lower Info row can end before the desired transport
            // column. At that edge, go up rather than pressing Right forever.
            if previousFrame == frame, previousDirection == direction, abs(dy) > 35 { direction = vertical }
            print("LEG132_FOCUS from=\(frame) target=\(target) direction=\(direction.rawValue)")
            previousFrame = frame; previousDirection = direction
            XCUIRemote.shared.press(direction)
        }
        record("132 remote focus failure")
        XCTFail("Remote input did not reach \(element.identifier): \(element.label)")
        #else
        reveal(element)
        XCTAssertTrue(element.isHittable)
        if select { element.tap() }
        #endif
    }
    #if os(tvOS)
    @MainActor private func focusedSnapshot(timeout: TimeInterval) throws -> XCUIElementSnapshot {
        let deadline = ProcessInfo.processInfo.systemUptime + timeout
        repeat {
            func focused(_ node: XCUIElementSnapshot) -> XCUIElementSnapshot? {
                // AVKit can mark both its enclosing environment and its real
                // focused cell. Directional routing needs the deepest item.
                return node.children.compactMap(focused).first ?? (node.hasFocus ? node : nil)
            }
            if let value = focused(try app.snapshot()) { return value }
        } while ProcessInfo.processInfo.systemUptime < deadline
        throw NSError(domain: "PictureshowFocus", code: 1, userInfo: [NSLocalizedDescriptionKey: "No focused accessibility node was observed"])
    }
    @MainActor private func nativeContextualAction(in player: XCUIElement, id: String, title: String, transportFrames: [CGRect]) throws -> XCUIElement {
        XCTAssertEqual(player.value as? String, "Controls hidden", "Contextual proof requires the native transport to remain hidden")
        XCTAssertTrue(transportFrames.allSatisfy { !$0.isEmpty }, "Record both real transport controls before idle")
        // UIAction identifiers do not consistently propagate to native AX. Match
        // the exact semantic title (with only its running count suffix allowed),
        // then distinguish the actual wide contextual control from retained bar
        // cells. A generic text descendant or the first label match cannot pass.
        let query = player.descendants(matching: .any).matching(NSPredicate(format: "identifier == %@ OR label == %@ OR label BEGINSWITH %@", id, title, title + " ("))
        let widestTransport = transportFrames.map(\.width).max() ?? 0
        let matches = query.allElementsBoundByIndex.filter { element in
            guard [.button, .cell, .other].contains(element.elementType),
                  element.identifier != "playNextTransport", element.identifier != "cancelNextTransport",
                  element.isEnabled, element.isHittable else { return false }
            let frame = element.frame
            guard !frame.isEmpty, player.frame.contains(frame), frame.width > widestTransport + 1 else { return false }
            return !transportFrames.contains { transport in
                abs(frame.minX - transport.minX) <= 1 && abs(frame.minY - transport.minY) <= 1
                    && abs(frame.width - transport.width) <= 1 && abs(frame.height - transport.height) <= 1
            }
        }
        XCTAssertEqual(matches.count, 1, "Expected exactly one enabled, hittable native contextual \(id) outside the retained transport geometry; capture precedes this query")
        return try XCTUnwrap(matches.count == 1 ? matches.first : nil)
    }
    @MainActor private func nativeAction(id: String, prefix: String) -> XCUIElement {
        let result = app.cells.matching(NSPredicate(format: "identifier == %@ OR label BEGINSWITH %@", id, prefix)).firstMatch
        revealNativeTransportIfHidden()
        // A just-dismissed sheet first returns focus to its actual native bar
        // opener. Do not send Up into AVKit's Info pane during that transition.
        if result.exists, waitForRemoteUI(timeout: 1.5, {
            guard result.isHittable, let focused = try? self.focusedSnapshot(timeout: 0.1) else { return false }
            return focused.elementType == .cell && abs(focused.frame.midY - result.frame.midY) < 70
        }) { return result }
        XCTAssertTrue(result.waitForExistence(timeout: 10), "The native transport must expose \(prefix)")
        return result
    }
    @MainActor private func revealNativeTransportIfHidden() {
        let player = app.descendants(matching: .any).matching(identifier: "pictureshowNativePlayer").firstMatch
        XCTAssertTrue(player.exists)
        if player.value as? String == "Controls visible" { return }
        var visible = false
        for direction in [XCUIRemote.Button.up, .down, .up] {
            if player.value as? String != "Controls hidden" { break }
            XCUIRemote.shared.press(direction)
            visible = waitForRemoteUI(timeout: 2) { player.value as? String == "Controls visible" }
            print("LEG132_HUD_REVEAL direction=\(direction.rawValue) visible=\(visible)")
            if visible { break }
        }
        if !visible { record("132 native HUD reveal failed") }
        XCTAssertTrue(visible, "The native HUD must actually be visible; AVKit retains stale hittable accessibility cells after hiding it")
    }
    #endif
}
