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
        if !app.buttons["signOut"].waitForExistence(timeout: 3) {
            activate(app.buttons["connect"])
            XCTAssertTrue(app.buttons["signIn"].waitForExistence(timeout: 15))
            activate(app.buttons["signIn"])
            XCTAssertTrue(app.buttons["signOut"].waitForExistence(timeout: 20))
        }
        try playAndResume(id: XCTUnwrap(config["hlsID"]), expected: "Server HLS", key: "hls")
        try playAndResume(id: XCTUnwrap(config["resumeID"]), expected: "Server HLS", key: "resume")
    }
    /// Isolates actual AVPlayer startup from catalog navigation when diagnosing a decoder
    /// or transport failure. The full slice test remains the navigation acceptance gate.
    func testDirectPlaybackStartup() throws {
        app.terminate()
        app.launchEnvironment["PICTURESHOW_TEST_DETAIL_ID"] = try XCTUnwrap(config["directID"])
        app.launch()
        let play = app.buttons["play"]
        reveal(play); activate(play)
        let method = app.staticTexts["playbackMethod"]
        XCTAssertTrue(method.waitForExistence(timeout: 30))
        capture("Direct startup initial preparation")
        let position = app.staticTexts["playbackPosition"]
        expectation(for: NSPredicate { _, _ in self.seconds(position.label) >= 3 }, evaluatedWith: position)
        waitForExpectations(timeout: 40)
        XCTAssertEqual(method.label, "Direct play")
        capture("Direct startup advancing video")
        #if os(tvOS)
        XCUIRemote.shared.press(.menu)
        #else
        activate(app.buttons["closePlayer"])
        #endif
        XCTAssertTrue(play.waitForExistence(timeout: 15))
    }
    func testSeriesSeasonsEpisodeAndTrackControls() throws {
        if !app.buttons["signOut"].waitForExistence(timeout: 3) {
            activate(app.buttons["connect"])
            XCTAssertTrue(app.buttons["signIn"].waitForExistence(timeout: 15))
            activate(app.buttons["signIn"])
            XCTAssertTrue(app.buttons["signOut"].waitForExistence(timeout: 20))
        }
        let seriesID = try XCTUnwrap(config["seriesID"])
        #if os(tvOS)
        if let libraryID = config["showsLibraryID"] {
            let library = app.buttons["item-\(libraryID)"].firstMatch
            reveal(library); activate(library)
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
        let play = app.buttons["play"]
        reveal(play); activate(play)
        XCTAssertTrue(app.buttons["playerTracks"].waitForExistence(timeout: 30))
        activate(app.buttons["playerTracks"])
        XCTAssertTrue(app.buttons["applyTracks"].waitForExistence(timeout: 15))
        capture("Audio and subtitle selection")
        activate(app.buttons["applyTracks"])
        XCTAssertTrue(app.staticTexts["playbackMethod"].waitForExistence(timeout: 30))
        XCTAssertEqual(app.staticTexts["playbackMethod"].label, "Server HLS")
        let position = app.staticTexts["playbackPosition"]
        let beforeChange = seconds(position.label)
        expectation(for: NSPredicate { _, _ in self.seconds(position.label) >= beforeChange + 5 }, evaluatedWith: position)
        waitForExpectations(timeout: 35)
        capture("Episode playback after track apply")
        #if os(tvOS)
        activate(app.buttons["playerTracks"])
        XCTAssertTrue(app.buttons["cancelTracks"].waitForExistence(timeout: 10))
        XCUIRemote.shared.press(.menu)
        XCTAssertTrue(app.buttons["closePlayer"].waitForExistence(timeout: 10), "Menu should cancel the track panel before leaving playback")
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
        XCUIRemote.shared.press(.menu)
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
        let attachment = XCTAttachment(string: code); attachment.name = "Quick Connect code for pilot approval"; attachment.lifetime = .keepAlways; add(attachment)
        // The private harness approves the displayed code through the pilot account and tests
        // the resulting home state. Without that harness, the cancellation path is exercised.
        if config["approveQuickConnect"] == "true" {
            XCTAssertTrue(app.buttons["signOut"].waitForExistence(timeout: 90))
        } else {
            activate(app.buttons["cancelQuick"])
            XCTAssertTrue(app.buttons["signIn"].waitForExistence(timeout: 10))
        }
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
        let method = app.staticTexts["playbackMethod"]
        XCTAssertTrue(method.waitForExistence(timeout: 30))
        capture("\(expected) initial preparation")
        XCTAssertEqual(method.label, expected)
        let position = app.staticTexts["playbackPosition"]
        let advanced = NSPredicate(format: "label != %@ AND label != %@", "0:00", "0:01")
        expectation(for: advanced, evaluatedWith: position)
        waitForExpectations(timeout: 35)
        // Respect the server's minimum video duration and watched percentage for resume.
        let minimum = max(Int(config[key + "ResumeSeconds"] ?? "6") ?? 6, seconds(position.label) + 6)
        let enoughProgress = NSPredicate { _, _ in
            let parts = position.label.split(separator: ":").compactMap { Int($0) }
            return parts.count == 2 && parts[0] * 60 + parts[1] >= minimum
        }
        expectation(for: enoughProgress, evaluatedWith: position)
        waitForExpectations(timeout: TimeInterval(minimum + 35))
        XCTAssertTrue(app.buttons["playerTracks"].exists)
        capture("\(expected) playing")
        #if os(tvOS)
        XCUIRemote.shared.press(.menu)
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
        let resumeMinimum = eligible ? max(2, seconds(saved.replacingOccurrences(of: "Resume from ", with: "")) + 2) : 2
        expectation(for: NSPredicate { _, _ in self.seconds(position.label) >= resumeMinimum }, evaluatedWith: position)
        waitForExpectations(timeout: 30)
        capture("\(expected) \(eligible ? "resumed" : "replayed short sample") after \(saved)")
        if key == "direct", let audio = config["directAudioIndex"], let subtitle = config["directSubtitleIndex"] {
            activate(app.buttons["playerTracks"])
            let audioChoice = app.buttons["audio-track-\(audio)"]
            reveal(audioChoice); activate(audioChoice)
            let subtitleChoice = app.buttons["subtitle-track-\(subtitle)"]
            reveal(subtitleChoice); activate(subtitleChoice)
            XCTAssertEqual(subtitleChoice.value as? String, "Selected")
            capture("Explicit audio and subtitle choices")
            let apply = app.buttons["applyTracks"]
            reveal(apply); activate(apply)
            expectation(for: NSPredicate(format: "label == %@", "Server HLS"), evaluatedWith: method)
            waitForExpectations(timeout: 30)
            let beforeChange = seconds(position.label)
            expectation(for: NSPredicate { _, _ in self.seconds(position.label) >= beforeChange + 5 }, evaluatedWith: position)
            waitForExpectations(timeout: 30)
            capture("Subtitle selection renegotiated to HLS")
            if let cue = config["directSubtitleCueSeconds"].flatMap(Int.init) {
                #if os(tvOS)
                for _ in 0..<6 where seconds(position.label) > cue {
                    let previous = seconds(position.label)
                    activate(app.buttons["seekBackward"])
                    expectation(for: NSPredicate { _, _ in self.seconds(position.label) <= previous - 5 }, evaluatedWith: position)
                    waitForExpectations(timeout: 10)
                }
                #endif
                if seconds(position.label) <= cue {
                    expectation(for: NSPredicate { _, _ in self.seconds(position.label) >= cue }, evaluatedWith: position)
                    waitForExpectations(timeout: 30)
                    capture("Selected subtitle during configured cue")
                }
            }
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
    private func capture(_ name: String) { let a = XCTAttachment(screenshot: app.screenshot()); a.name = name; a.lifetime = .keepAlways; add(a) }
}
