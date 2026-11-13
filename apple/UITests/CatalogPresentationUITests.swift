// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import XCTest
import Network
import UIKit
#if os(tvOS)
import Vision
#endif

/// Run this finite native flow on an owned fresh clone. All catalog data is
/// synthetic and served over ordinary Jellyfin HTTP requests on loopback.
final class CatalogPresentationUITests: XCTestCase {
    #if os(tvOS)
    private var focusedFillSamples: [String: [Double]] = [:]
    #endif
    /// Uses the real OS service, not an accessibility environment projection.
    /// Native setup/activation is performed with VoiceOver disabled; this test
    /// claims only the speech and forward/back traversal the service observes.
    @MainActor func testNativeVoiceOverSpeechTraversal() throws {
        guard #available(iOS 27.0, tvOS 27.0, *) else {
            throw XCTSkip("XCUIVoiceOverService requires iOS/tvOS 27; 26.5 cannot run this speech test")
        }
        continueAfterFailure = false
        // XCTest runs registered teardown even when its fatal assertion stops
        // this test before Swift defer can run. Do not leak the real reader
        // into another test sharing the owned simulator.
        addTeardownBlock {
            try await MainActor.run {
                let reader = XCUIDevice.shared.voiceOverService
                if reader.isEnabled { try reader.disable() }
                XCTAssertFalse(reader.isEnabled, "Teardown must leave real VoiceOver disabled")
            }
        }
        let service = XCUIDevice.shared.voiceOverService
        if service.isEnabled { try service.disable() }
        XCTAssertFalse(service.isEnabled)
        #if os(iOS)
        XCUIDevice.shared.orientation = .portrait
        #endif
        let fixture = try CatalogPresentationHTTPFixture()
        defer { fixture.stop() }
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launchEnvironment["PICTURESHOW_TEST_SERVER"] = fixture.address
        app.launchEnvironment["PICTURESHOW_TEST_USERNAME"] = "Catalog speech fixture viewer"
        app.launchEnvironment["PICTURESHOW_TEST_PASSWORD"] = "synthetic-catalog-speech-password"
        app.launch()
        defer { app.terminate() }
        var transcript: [String] = []
        var hasAcceptedReaderUtterance = false
        var initialForwardServiceResetUsed = false
        var didRecoverTimedOutIntroduction = false
        func attachSpeech(_ name: String) {
            let text = XCTAttachment(string: transcript.joined(separator: "\n") + "\nService: " + service.debugDescription)
            text.name = name + " actual VoiceOver utterances"; text.lifetime = .keepAlways; add(text)
            capture(name, app: app)
        }
        func disableReader() {
            do {
                if service.isEnabled { try service.disable() }
                XCTAssertFalse(service.isEnabled, "The owned simulator must leave the real VoiceOver service disabled")
            } catch { XCTFail("Could not disable real VoiceOver service: \(error)") }
        }
        defer { disableReader() }
        func record(_ operation: String, _ output: XCUIVoiceOverService.Output) -> String {
            let utterance = output.utterance.trimmingCharacters(in: .whitespacesAndNewlines)
            let entry = "\(operation): \(utterance)"
            if transcript.last != entry { transcript.append(entry) }
            if utterance.isEmpty { attachSpeech("132 VoiceOver missing speech before failure"); disableReader() }
            XCTAssertFalse(utterance.isEmpty, "A real VoiceOver navigation operation must produce speech")
            if !utterance.isEmpty { hasAcceptedReaderUtterance = true }
            // This combined phrase is the visual-only pinned context, not an
            // ordinary heading, card or independently read sort control.
            let tokens = words(utterance)
            let spokeQuietContext = Set(["fixture", "library", "name", "ascending"]).isSubset(of: tokens)
            if spokeQuietContext { attachSpeech("132 VoiceOver exposed quiet context before failure"); disableReader() }
            XCTAssertFalse(spokeQuietContext, "The quiet pinned library/sort context must not be spoken")
            return utterance
        }
        func words(_ utterance: String) -> Set<String> {
            Set(utterance.lowercased().components(separatedBy: CharacterSet.alphanumerics.inverted).filter { !$0.isEmpty })
        }
        func readSpeech(_ operation: String, _ action: () throws -> XCUIVoiceOverService.Output) throws -> XCUIVoiceOverService.Output? {
            do { return try action() }
            catch {
                let value = error as NSError
                let entry = "\(operation) error: \(value.domain) \(value.code) \(value.localizedDescription)"
                if transcript.last != entry { transcript.append(entry) }
                // Only this documented readiness error is transient. Other
                // service failures retain their actual error and fail the test.
                if value.domain == XCUIVoiceOverServiceErrorDomain,
                   value.code == XCUIVoiceOverService.Error.Code.noSpeech.rawValue { return nil }
                attachSpeech(operation + " API error before throw")
                throw error
            }
        }
        func settledSpeech(_ operation: String, after previous: String? = nil,
                           first: XCUIVoiceOverService.Output? = nil,
                           accepts: (String) -> Bool = { _ in true }) throws -> String {
            var candidate = first
            var spoken = ""
            var unexpected: (any Error)?
            // The service delivers focus/speech updates to the test process.
            // Keep this actor's run loop live while polling instead of sleeping
            // it; a synchronous currentSpeech result can precede that update.
            let deadline = ProcessInfo.processInfo.systemUptime + 10
            func sample() -> Bool {
                do {
                    let output: XCUIVoiceOverService.Output?
                    if let initial = candidate { output = initial; candidate = nil }
                    else { output = try readSpeech(operation + " current", { try service.currentSpeech() }) }
                    guard let output, !output.utterance.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return false }
                    spoken = record(operation + " observed", output)
                    return spoken != previous && accepts(spoken)
                } catch { unexpected = error; return true }
            }
            var ready = sample()
            while !ready && ProcessInfo.processInfo.systemUptime < deadline {
                RunLoop.current.run(until: Date(timeIntervalSinceNow: 0.1))
                ready = sample()
            }
            if let unexpected { throw unexpected }
            if !ready { attachSpeech(operation + " before speech readiness failure"); disableReader() }
            XCTAssertTrue(ready, "One real reader operation must produce nonempty changed speech within the existing readiness bound; do not issue another move while output is stale")
            return spoken
        }
        func movedSpeech(_ operation: String, after previous: String,
                         _ action: () throws -> XCUIVoiceOverService.Output) throws -> String {
            let output = try readSpeech(operation + " move", action)
            return try settledSpeech(operation, after: previous, first: output)
        }
        #if os(tvOS)
        // Setup observation only: this first-run system dialog is absent from
        // app-only AX. It never substitutes for an actual reader utterance.
        func visibleFocusedSystemIntroduction() throws -> Bool {
            let shot = XCUIScreen.main.screenshot()
            let image = shot.image
            let attachment = XCTAttachment(image: image)
            attachment.name = "132 VoiceOver exact system introduction setup pixels"
            attachment.lifetime = .keepAlways; add(attachment)
            let tree = XCTAttachment(string: app.debugDescription)
            tree.name = "132 VoiceOver exact system introduction setup pixels accessibility"
            tree.lifetime = .keepAlways; add(tree)
            guard let source = image.cgImage else { return false }
            let request = VNRecognizeTextRequest()
            request.recognitionLevel = .accurate
            request.recognitionLanguages = ["en-US"]
            request.usesLanguageCorrection = false
            try VNImageRequestHandler(cgImage: source, options: [:]).perform([request])
            let rows: [(text: String, box: CGRect)] = (request.results ?? []).compactMap { row in
                guard let candidate = row.topCandidates(1).first else { return nil }
                return (candidate.string.trimmingCharacters(in: .whitespacesAndNewlines), row.boundingBox)
            }
            let diagnostic = XCTAttachment(string: rows.map { "\($0.text) \($0.box)" }.joined(separator: "\n"))
            diagnostic.name = "132 VoiceOver exact system introduction setup OCR"
            diagnostic.lifetime = .keepAlways; add(diagnostic)
            let titleRows = rows.filter { ["VoiceOver Navigation Updates", "VoiceOver Navigation", "Updates"].contains($0.text) }
            let titleText = titleRows.sorted { $0.box.midY > $1.box.midY }.map(\.text).joined(separator: " ")
            let okRows = rows.filter { $0.text == "OK" }
            guard titleText == "VoiceOver Navigation Updates", okRows.count == 1,
                  let title = titleRows.map(\.box).reduce(nil as CGRect?, { $0?.union($1) ?? $1 }),
                  let ok = okRows.first?.box,
                  title.minX > 0.2, title.maxX < 0.8, ok.minX > 0.2, ok.maxX < 0.8,
                  ok.maxY < title.minY, abs(ok.midX - title.midX) < 0.15 else { return false }
            let width = source.width, height = source.height
            var rgba = [UInt8](repeating: 0, count: width * height * 4)
            let rendered = rgba.withUnsafeMutableBytes { bytes -> Bool in
                guard let context = CGContext(data: bytes.baseAddress, width: width, height: height,
                    bitsPerComponent: 8, bytesPerRow: width * 4,
                    space: CGColorSpace(name: CGColorSpace.sRGB)!,
                    bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue) else { return false }
                context.draw(source, in: CGRect(x: 0, y: 0, width: width, height: height)); return true
            }
            guard rendered else { return false }
            // Sample the actual pale focused-OK fill on both sides of its dark
            // word, never the white title glyphs or the dimmed underlying app.
            for x in [ok.minX - 2 * ok.width, ok.maxX + 2 * ok.width] {
                let px = Int(x * CGFloat(width)), py = Int((1 - ok.midY) * CGFloat(height))
                guard px >= 1, px < width - 1, py >= 1, py < height - 1 else { return false }
                var samples: [UInt8] = []
                for dy in -1...1 { for dx in -1...1 {
                    let offset = ((py + dy) * width + px + dx) * 4
                    samples.append(min(rgba[offset], rgba[offset + 1], rgba[offset + 2]))
                } }
                guard samples.sorted()[samples.count / 2] >= 192 else { return false }
            }
            transcript.append("enable timeout setup: exact visible VoiceOver Navigation Updates and one pale focused OK; not a speech observation")
            return true
        }
        #endif
        func enableReader(at anchor: XCUIElement) throws {
            do { try service.enable() }
            catch {
                transcript.append("enable API error: \(error)")
                attachSpeech("132 VoiceOver enable API error actual state")
                #if os(tvOS)
                let value = error as NSError
                // The observed first-run OS dialog can outlive a failed enable
                // acknowledgement. Recover only its proved enabled+intro state;
                // an enabled flag or timeout alone never authorizes input.
                guard !didRecoverTimedOutIntroduction, !hasAcceptedReaderUtterance,
                      value.domain == "com.apple.dt.XCTest.XCTFuture", value.code == 1000,
                      value.localizedDescription == "Timed out while enabling VoiceOver.",
                      service.isEnabled else { throw error }
                let output = try readSpeech("enable timeout actual current", { try service.currentSpeech() })
                let introductionSpeech: String?
                if let output {
                    guard output.utterance.hasPrefix("VoiceOver Navigation Updates") else { throw error }
                    introductionSpeech = record("enable timeout actual introduction", output)
                } else {
                    // The partially started speech channel may have no utterance.
                    // Exact OS title, unique OK and its actual pale focused fill
                    // are setup evidence only; all speech must still be real.
                    guard try visibleFocusedSystemIntroduction() else { throw error }
                    introductionSpeech = nil
                }
                attachSpeech("132 VoiceOver timed-out enable exact OS introduction before one Select")
                didRecoverTimedOutIntroduction = true
                XCUIRemote.shared.press(.select)
                _ = try settledSpeech("after timed-out introduction OK", after: introductionSpeech,
                                      accepts: { !$0.hasPrefix("VoiceOver Navigation Updates") && !$0.lowercased().hasPrefix("ok") })
                attachSpeech("132 VoiceOver timed-out introduction after one native Select actual changed speech")
                #else
                throw error
                #endif
            }
            XCTAssertTrue(service.isEnabled)
            // Capture before querying speech: a newly enabled reader may not
            // yet have a focused element and currentSpeech can throw noSpeech.
            attachSpeech("132 VoiceOver enabled actual system state before first speech")
            #if os(iOS)
            if !hasAcceptedReaderUtterance {
                // A real scene activation gives a newly enabled reader its
                // normal app-entry focus event without activating an app control.
                // Preserve the current navigation stack and require its target back.
                XCUIDevice.shared.press(.home)
                app.activate()
                XCTAssertTrue(app.wait(for: .runningForeground, timeout: 10))
                XCTAssertTrue(anchor.waitForExistence(timeout: 10) && anchor.isHittable)
                attachSpeech("132 VoiceOver initial native foreground restored before speech")
            }
            // Keep the requested native target visible, but establish reader
            // focus through the service itself. Enabled state alone does not
            // prove a coordinate tap will route as exploration rather than
            // activating the control. The bounded RunLoop poll below permits
            // actual reader events to arrive without another input operation.
            XCTAssertTrue(anchor.exists && anchor.isHittable)
            #endif
            #if os(iOS)
            // Enabling the reader does not guarantee initial focus or speech.
            // If it has no current speech, request focus with one documented
            // next-element operation; the existing live-run-loop bound requires
            // an actual nonempty utterance before any traversal assertion.
            func initialOutput() throws -> XCUIVoiceOverService.Output? {
                var output = try readSpeech("reader enabled current", { try service.currentSpeech() })
                if output == nil {
                    output = try readSpeech("reader initial focus forward", { try service.moveForward() })
                }
                return output
            }
            let output: XCUIVoiceOverService.Output?
            do { output = try initialOutput() }
            catch {
                let value = error as NSError
                // This is a different domain from the documented noSpeech case.
                // One recorded OS-service reset is allowed only before any real
                // utterance/traversal, for the exact observed bootstrap send error.
                // All later service errors still throw; the test itself never restarts.
                guard !hasAcceptedReaderUtterance, !initialForwardServiceResetUsed,
                      value.domain == "AXVoiceOverAutomationErrorDomain", value.code == 3,
                      value.localizedDescription == "Failed to send moveForward command",
                      service.isEnabled else { throw error }
                initialForwardServiceResetUsed = true
                transcript.append("initial forward service reset: one disable then enable before first accepted utterance")
                attachSpeech("132 VoiceOver exact initial-forward send failure before one service reset")
                try service.disable()
                XCTAssertFalse(service.isEnabled)
                attachSpeech("132 VoiceOver initial service reset actual disabled state")
                try service.enable()
                XCTAssertTrue(service.isEnabled)
                attachSpeech("132 VoiceOver initial service reset actual enabled state")
                output = try initialOutput()
            }
            let initial = try settledSpeech("reader enabled", first: output)
            #else
            var output = try readSpeech("reader enabled current", { try service.currentSpeech() })
            if output == nil {
                // One real next-element operation establishes initial reader
                // focus. Subsequent readiness uses the existing bounded poll.
                output = try readSpeech("reader initial focus forward", { try service.moveForward() })
            }
            let initial = try settledSpeech("reader enabled", first: output)
            #endif
            attachSpeech("132 VoiceOver enabled actual first speech")
            #if os(tvOS)
            // A fresh tvOS27 sandbox presents this native introduction. The
            // recorded system alert has one focused OK button. Dismiss only
            // that observed introduction with one real remote Select.
            let introduction = "VoiceOver Navigation Updates"
            if initial.hasPrefix(introduction) {
                XCUIRemote.shared.press(.select)
                _ = try settledSpeech("after system introduction OK", after: initial,
                                      accepts: { !$0.hasPrefix(introduction) })
                attachSpeech("132 VoiceOver system introduction after native Select")
            }
            #endif
        }
        enum ReaderDirection { case up, down, left, right }
        #if os(tvOS)
        // tvOS Navigation mode follows the native remote focus engine. Exercise
        // that real input with VoiceOver enabled, then read actual OS speech;
        // generic next/previous service commands can leave native focus still.
        func roundTrip(_ name: String, anchor: XCUIElement,
                       away: ReaderDirection, back: ReaderDirection,
                       matches: (String) -> Bool) throws {
            defer { attachSpeech(name) }
            focus(anchor, app: app)
            XCTAssertTrue(ownsNativeFocus(anchor, app: app), "The real remote must reach the expected control or its native Form row")
            let spoken = try settledSpeech(name + " focused", accepts: matches)
            XCTAssertTrue(matches(spoken))
            func button(_ direction: ReaderDirection) -> XCUIRemote.Button {
                switch direction { case .up: .up; case .down: .down; case .left: .left; case .right: .right }
            }
            XCUIRemote.shared.press(button(away))
            let previous = try settledSpeech(name + " remote away", after: spoken)
            XCTAssertFalse(ownsNativeFocus(anchor, app: app), "The native remote must actually leave the target and its native Form row")
            XCUIRemote.shared.press(button(back))
            let returned = try settledSpeech(name + " remote return", after: previous, accepts: matches)
            XCTAssertTrue(ownsNativeFocus(anchor, app: app), "One inverse native remote move must return to the same control or its native Form row")
            XCTAssertTrue(matches(returned), "Actual VoiceOver must speak the same meaningful control/state on return")
        }
        #else
        func roundTrip(_ name: String, anchor: XCUIElement, away: ReaderDirection, back: ReaderDirection, matches: (String) -> Bool) throws {
            defer { attachSpeech(name) }
            var spoken = try settledSpeech(name + " current")
            for move in 0..<32 {
                if matches(spoken) { break }
                spoken = try movedSpeech(name + " forward \(move + 1)", after: spoken, { try service.moveForward() })
            }
            if !matches(spoken) { attachSpeech(name + " before unreachable target failure"); disableReader() }
            XCTAssertTrue(matches(spoken), "Actual VoiceOver must reach the expected app control/state within 32 changed forward moves. Transcript: \(transcript)")
            let previous = try movedSpeech(name + " backward", after: spoken, { try service.moveBackward() })
            XCTAssertNotEqual(previous, spoken, "Backward must actually leave the observed app control")
            let returned = try movedSpeech(name + " forward return", after: previous, { try service.moveForward() })
            if !matches(returned) { attachSpeech(name + " before return failure"); disableReader() }
            XCTAssertTrue(matches(returned), "Forward must return to the same meaningful app control/state")
        }
        #endif

        XCTAssertTrue(app.buttons["connect"].waitForExistence(timeout: 15), "Run this speech test on a fresh owned simulator sandbox")
        activate(app.buttons["connect"], app: app)
        let signIn = app.buttons["signIn"]
        XCTAssertTrue(signIn.waitForExistence(timeout: 15))
        #if os(tvOS)
        if app.keyboards.firstMatch.exists { XCUIRemote.shared.press(.menu) }
        #else
        let done = app.buttons["dismissSignInKeyboard"]
        // Keyboard toolbars can leave an existing AX node without an activation
        // frame after the synthetic credentials dismiss the software keyboard.
        // Query hittability only for a real visible keyboard control.
        if app.keyboards.firstMatch.exists && done.exists && !done.frame.isEmpty
            && app.windows.firstMatch.frame.intersects(done.frame) && done.isHittable { done.tap() }
        #endif
        activate(signIn, app: app)
        let library = app.buttons["item-catalog-library"]
        XCTAssertTrue(library.waitForExistence(timeout: 20))
        fixture.failNextPages(1)
        activate(library, app: app)
        let series = app.buttons["item-catalog-series"]
        XCTAssertTrue(series.waitForExistence(timeout: 15))
        #if os(tvOS)
        focus(series, app: app)
        #else
        reveal(series, app: app)
        #endif
        let fullSeriesTitle = "The fixture series with a long readable catalog title"
        XCTAssertEqual(series.elementType, .button)
        XCTAssertEqual(series.label, fullSeriesTitle + ", 2026", "The entire native card identity and role must be available independently of the speech fragment")
        try enableReader(at: series)
        try roundTrip("132 VoiceOver catalog title identity", anchor: series, away: .up, back: .down) {
            // CI7 returned the full unique title but ended mid-role at Butt.
            // This is observed output, not a documented API length limit.
            $0.localizedCaseInsensitiveContains(fullSeriesTitle)
        }
        // Reader navigation reaches a failed page's real footer control while
        // the scrolling header is gone and the quiet visual strip is present.
        try roundTrip("132 VoiceOver catalog Retry with quiet pinned context", anchor: app.buttons["pageRetry"], away: .up, back: .down) {
            words($0).contains("retry") && words($0).contains("button")
        }
        XCTAssertLessThan(app.staticTexts["libraryHeading"].frame.maxY, 0,
                          "The actual reader Retry traversal must reach a scrolled catalog")
        XCTAssertFalse(app.staticTexts.matching(identifier: "librarySortStrip").firstMatch.exists,
                       "The visual pinned context must remain absent from the reader tree")
        disableReader()

        activate(series, app: app)
        let seasonTwo = app.buttons["season-catalog-season-2"]
        XCTAssertTrue(seasonTwo.waitForExistence(timeout: 15)); activate(seasonTwo, app: app)
        #if os(iOS)
        // The episode grid is lazy and may be below the visible season shelf.
        // Reveal its real heading before requiring the dependent tile to attach.
        reveal(app.staticTexts["Episodes"].firstMatch, app: app)
        #endif
        XCTAssertTrue(app.buttons["item-catalog-episode-2"].waitForExistence(timeout: 15))
        #if os(tvOS)
        focus(app.buttons["play"], app: app)
        #else
        reveal(app.buttons["play"], app: app)
        #endif
        let watched = app.buttons["watched"]
        XCTAssertTrue(waitUntil { !(watched.value as? String ?? "").isEmpty }, "A fresh watched action must publish its actual current value")
        let watchedAction = watched.label
        let watchedState = try XCTUnwrap(watched.value as? String)
        XCTAssertFalse(watchedAction.isEmpty); XCTAssertFalse(watchedState.isEmpty)
        try enableReader(at: watched)
        try roundTrip("132 VoiceOver detail watched action and current state", anchor: watched, away: .left, back: .right) {
            $0.localizedCaseInsensitiveContains(watchedAction) && words(watchedState).isSubset(of: words($0))
        }
        try roundTrip("132 VoiceOver selected season", anchor: seasonTwo, away: .left, back: .right) {
            $0.localizedCaseInsensitiveContains("Season 2") && words($0).contains("selected")
        }
        // Preserve any real boundary speech in the transcript, without claiming
        // that a service next-element operation emulates a remote/reader gesture.
        #if os(iOS)
        let selectedSeasonSpeech = try settledSpeech("detail selected season before boundary observation")
        let afterSeasonSpeech = try movedSpeech("detail after selected season", after: selectedSeasonSpeech, { try service.moveForward() })
        _ = try movedSpeech("detail back to selected season", after: afterSeasonSpeech, { try service.moveBackward() })
        #endif
        attachSpeech("132 VoiceOver detail traversal after selected season")
        disableReader()
        back(app); back(app)
        activateSettingsChrome(app)
        let conversion = app.descendants(matching: .any).matching(identifier: "conversionNoticeToggle").firstMatch
        XCTAssertTrue(conversion.waitForExistence(timeout: 15))
        #if os(tvOS)
        focus(conversion, app: app)
        #else
        reveal(conversion, app: app)
        #endif
        try enableReader(at: conversion)
        try roundTrip("132 VoiceOver native conversion switch state", anchor: conversion, away: .up, back: .down) {
            $0.localizedCaseInsensitiveContains("Conversion notice") && words($0).contains("on")
        }
    }

    func testNativeCatalogSortPagingSeasonRetentionAndConversionPreference() throws {
        continueAfterFailure = false
        #if os(iOS)
        XCUIDevice.shared.orientation = .portrait
        #endif
        #if os(iOS)
        // Native iPad top tabs leave the full width for more poster columns.
        // Supply enough real HTTP rows that Retry requires actual scrolling,
        // so pinned context cannot pass while its ordinary heading is visible.
        let pageSize = UIDevice.current.userInterfaceIdiom == .pad ? 24 : 6
        #else
        let pageSize = 6
        #endif
        let fixture = try CatalogPresentationHTTPFixture(pageSize: pageSize)
        defer { fixture.stop() }
        let app = XCUIApplication()
        #if os(tvOS)
        app.launchEnvironment["PICTURESHOW_CATALOG_GEOMETRY_PROBE"] = "1"
        #endif
        let projectedLargeType = ProcessInfo.processInfo.environment["PICTURESHOW_TEST_DYNAMIC_TYPE"] == "accessibility3"
        if projectedLargeType { app.launchEnvironment["PICTURESHOW_TEST_DYNAMIC_TYPE"] = "accessibility3" }

        #if os(iOS)
        app.launchArguments += ["-UIPreferredContentSizeCategoryName", UIContentSizeCategory.extraExtraExtraLarge.rawValue]
        #endif
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
        // Keyboard toolbars can leave an existing AX node without an activation
        // frame after the synthetic credentials dismiss the software keyboard.
        // Query hittability only for a real visible keyboard control.
        if app.keyboards.firstMatch.exists && done.exists && !done.frame.isEmpty
            && app.windows.firstMatch.frame.intersects(done.frame) && done.isHittable { done.tap() }
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
        capture("132 catalog actual content pane before geometry", app: app)
        // The persistent iPad sidebar is outside this catalog's GeometryReader.
        // Use the real visible scroll pane containing this exact card, not the
        // whole app window or an unrelated last scroll view.
        let catalogPanes = app.scrollViews.containing(.button, identifier: "item-catalog-series")
            .allElementsBoundByIndex.filter { $0.isHittable }
        XCTAssertEqual(catalogPanes.count, 1, "Exactly one visible catalog scroll pane must contain the series button")
        let catalogPane = try XCTUnwrap(catalogPanes.first)
        XCTAssertTrue(catalogPane.exists)
        let viewport = catalogPane.frame
        XCTAssertFalse(viewport.isEmpty)
        XCTAssertTrue(app.windows.firstMatch.frame.contains(viewport), "The measured catalog pane must be inside the actual app window")
        XCTAssertTrue(catalogPane.buttons["item-catalog-series"].exists)
        let firstCards = [seriesCard] + (1...5).map { app.buttons["item-catalog-\($0)"] }
        let firstRow = firstCards.filter { $0.exists && abs($0.frame.minY - seriesCard.frame.minY) < 8 }.sorted { $0.frame.minX < $1.frame.minX }
        let lastInRow = try XCTUnwrap(firstRow.last)
        XCTAssertEqual(lastInRow.frame.maxX, viewport.maxX - 20, accuracy: 2, "Flexible columns must fill the catalog pane through its trailing page gutter")
        XCTAssertEqual(seriesCard.frame.width, lastInRow.frame.width, accuracy: 2)
        XCTAssertEqual(seriesCard.frame.minX, viewport.minX + 20, accuracy: 2)
        let sortField = app.buttons["librarySort"]
        let direction = app.buttons["sortDirection"]
        XCTAssertTrue(sortField.exists)
        assertSingleLineTouchHeight(sortField)
        assertSingleLineTouchHeight(direction)
        XCTAssertEqual(sortField.frame.height, direction.frame.height, accuracy: 2)
        XCTAssertEqual(sortField.frame.width, direction.frame.width, accuracy: 2, "Both halves of sort retain stable equal-width targets")
        #endif
        #if os(tvOS)
        // Entry focus may land on the selected sort chip. Move it to the real
        // card before measuring both controls in their required resting state.
        focus(seriesCard, app: app)
        XCTAssertTrue(seriesCard.hasFocus)
        assertNativeButtonPixels(app.buttons["sort-SortName"], app: app, style: "selected choice", text: "Name")
        assertNativeButtonPixels(app.buttons["sortDirection"], app: app, style: "secondary action", text: "Ascending")
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
        #if os(iOS)
        reveal(app.buttons["item-catalog-\(pageSize - 1)"], app: app)
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
        let columns = firstCards.filter { $0.exists }.filter {
            let frame = $0.frame
            // Native poster lift grows around its centre, moving only the
            // focused card's top. Row centres remain aligned during that lift.
            return abs(frame.midY - secondFrame.midY) < max(8, max(frame.height, secondFrame.height) * 0.025)
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
        // Capture before the assertion so a regression includes the actual
        // scrolled strip node rather than only the earlier top-of-grid snapshot.
        capture("132 catalog pinned library sort context", app: app)
        // An automation-only wrapper is not spoken text. The observed
        // regression was this exact StaticText, which the old source exposes.
        let strip = app.staticTexts.matching(identifier: "librarySortStrip").firstMatch
        if strip.exists { print("LEG132_QUIET_STRIP_NODE \(strip.debugDescription)") }
        XCTAssertFalse(strip.exists, "The visual context strip must stay silent to VoiceOver")
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
        #if os(tvOS)
        focus(app.buttons["play"], app: app)
        assertNativeButtonPixels(app.buttons["play"], app: app, style: "focused primary", focused: true)
        capture("132 round2 TV Play focused single system focus", app: app)
        focus(app.buttons["watched"], app: app)
        assertNativeButtonPixels(app.buttons["watched"], app: app, style: "focused secondary", focused: true)
        capture("132 round2 TV secondary focused Play resting accent", app: app)
        let primaryFill = try XCTUnwrap(focusedFillSamples["focused primary"])
        let secondaryFill = try XCTUnwrap(focusedFillSamples["focused secondary"])
        // Capture both actual roles before testing either fill. A failing sample
        // must preserve its native comparison rather than hide it behind a stop.
        for channels in [primaryFill, secondaryFill] {
            XCTAssertGreaterThanOrEqual(channels.min() ?? 0, 0.75, "Native focused fill must remain pale")
        }
        for (primaryChannel, secondaryChannel) in zip(primaryFill, secondaryFill) {
            XCTAssertEqual(primaryChannel, secondaryChannel, accuracy: 0.04, "Primary and secondary use one native neutral focus fill")
        }
        assertNativeButtonPixels(seasonOne, app: app, style: "selected season choice", text: "Season 1")
        assertNativeButtonPixels(app.buttons["favorite"], app: app, style: "detail secondary action", text: "Add favorite")
        assertNativeButtonPixels(app.buttons["playOnDevice"], app: app, style: "native Menu secondary action", text: "Play on TV")
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
        activateSettingsChrome(app)
        let toggle = app.switches["conversionNoticeToggle"]
        #if os(tvOS)
        let playbackHeader = app.staticTexts["playbackSectionHeader"]
        let audioFooter = app.staticTexts["audioOutputExplanation"]
        XCTAssertTrue(playbackHeader.waitForExistence(timeout: 10) && audioFooter.exists)
        assertNativeButtonPixels(playbackHeader, app: app, style: "Round3 Settings section header AA", text: "Playback")
        assertNativeButtonPixels(audioFooter, app: app, style: "Round3 Settings output footer AA", text: "Apple TV chooses")
        assertSettingsPickerChanges(app)
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
    /// Opening an empty native picker fails before any selection or save claim.
    private func assertSettingsPickerChanges(_ app: XCUIApplication) {
        let bitrate = app.descendants(matching: .any).matching(identifier: "maxBitrate").firstMatch
        let subtitle = app.buttons["subtitleSize"]
        func shows(_ picker: XCUIElement, _ value: String) -> Bool {
            picker.value as? String == value || picker.label.hasSuffix(value) || picker.staticTexts[value].exists
        }
        func choose(_ picker: XCUIElement, _ value: String, current: String, choices: [String]) {
            activate(picker, app: app)
            for choice in choices {
                XCTAssertTrue(app.otherElements.matching(NSPredicate(format: "label == %@", choice)).firstMatch.waitForExistence(timeout: 5), "The native picker must expose its real choice: \(choice)")
            }
            let selected = app.otherElements.matching(NSPredicate(format: "label == %@", current)).firstMatch
            XCTAssertTrue(selected.isSelected, "The native checkmark must identify the actual saved selection")
            capture("132 native Settings picker choices \(value)", app: app)
            activate(app.otherElements.matching(NSPredicate(format: "label == %@", value)).firstMatch, app: app)
            XCTAssertTrue(waitUntil { shows(picker, value) }, "Selecting a choice must save it and return to Settings")
        }
        XCTAssertTrue(bitrate.waitForExistence(timeout: 10))
        capture("132 named native Settings menus before assertions", app: app)
        for (picker, title) in [(bitrate, "Max streaming bitrate"), (subtitle, "Subtitle size"), (app.buttons["subtitleBackground"], "Subtitle background")] {
            XCTAssertTrue(picker.exists)
            XCTAssertEqual(picker.label.components(separatedBy: title).count - 1, 1, "The actual native menu must announce its field name exactly once")
            XCTAssertFalse((picker.value as? String ?? "").isEmpty, "The named native menu must retain its current value")
        }
        XCTAssertTrue(shows(bitrate, "Auto (200 Mbps)"))
        focus(bitrate, app: app)
        assertNativeButtonPixels(subtitle, app: app, style: "Settings unfocused value", text: "System")
        focus(subtitle, app: app)
        assertNativeButtonPixels(subtitle, app: app, style: "Settings focused value", focused: true, text: "System")
        let rates = ["Auto (200 Mbps)", "120 Mbps", "80 Mbps", "40 Mbps", "20 Mbps", "10 Mbps", "4 Mbps"]
        choose(bitrate, "40 Mbps", current: "Auto (200 Mbps)", choices: rates)
        choose(bitrate, "Auto (200 Mbps)", current: "40 Mbps", choices: rates)
        XCTAssertTrue(subtitle.exists && shows(subtitle, "System"))
        let sizes = ["System", "Small", "Normal", "Large", "ExtraLarge"]
        choose(subtitle, "Large", current: "System", choices: sizes)
        choose(subtitle, "System", current: "Large", choices: sizes)
        capture("132 native Settings selections restored", app: app)
    }

    /// Measure actual screenshot pixels, never the declared SwiftUI colors. A
    /// central crop excludes the platter outline/shadow, so a white focus border
    /// cannot hide the previous dark-on-slate label defect. Native text has many
    /// opaque interior pixels; antialiasing edges are not treated as label ink.
    private func assertNativeButtonPixels(_ element: XCUIElement, app: XCUIApplication, style: String, focused: Bool = false, text: String? = nil,
                                               file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertTrue(element.exists, file: file, line: line)
        XCTAssertEqual(ownsNativeFocus(element, app: app), focused, "Sample the required actual focus state: \(style)", file: file, line: line)
        let image = XCUIScreen.main.screenshot().image
        let pixelAttachment = XCTAttachment(image: image)
        pixelAttachment.name = "132 TV actual pixel sample " + style
        pixelAttachment.lifetime = .keepAlways
        add(pixelAttachment)
        let treeAttachment = XCTAttachment(string: app.debugDescription)
        treeAttachment.name = "132 TV actual pixel sample " + style + " accessibility"
        treeAttachment.lifetime = .keepAlways
        add(treeAttachment)
        guard let source = image.cgImage else { XCTFail("Missing screenshot pixels", file: file, line: line); return }
        let scaleX = CGFloat(source.width) / app.frame.width
        let scaleY = CGFloat(source.height) / app.frame.height
        let frame: CGRect
        if let text {
            // SwiftUI can omit child Text nodes or give one the whole button's
            // frame. Recognize the expected words inside this button's pixels
            // so an icon/checkmark cannot conceal low-contrast label ink.
            let control = CGRect(x: (element.frame.minX - app.frame.minX) * scaleX,
                                 y: (element.frame.minY - app.frame.minY) * scaleY,
                                 width: element.frame.width * scaleX, height: element.frame.height * scaleY).integral
            guard let buttonPixels = source.cropping(to: control) else {
                XCTFail("No visible control pixels: \(style)", file: file, line: line); return
            }
            let request = VNRecognizeTextRequest()
            request.recognitionLevel = .accurate
            request.recognitionLanguages = ["en-US"]
            request.usesLanguageCorrection = false
            do { try VNImageRequestHandler(cgImage: buttonPixels, options: [:]).perform([request]) }
            catch { XCTFail("Could not locate actual label glyphs: \(error)", file: file, line: line); return }
            let matches: [CGRect] = (request.results ?? []).compactMap { observation in
                for candidate in observation.topCandidates(3) {
                    if let range = candidate.string.range(of: text, options: .caseInsensitive),
                       let box = try? candidate.boundingBox(for: range) { return box.boundingBox }
                }
                return nil
            }
            XCTAssertEqual(matches.count, 1, "Locate exactly the expected text independently from its icon/checkmark: \(text)", file: file, line: line)
            guard matches.count == 1, let glyphs = matches.first else { return }
            // Vision uses a bottom-left origin; CGImage crops use top-left.
            let local = CGRect(x: glyphs.minX * CGFloat(buttonPixels.width),
                               y: (1 - glyphs.maxY) * CGFloat(buttonPixels.height),
                               width: glyphs.width * CGFloat(buttonPixels.width),
                               height: glyphs.height * CGFloat(buttonPixels.height)).insetBy(dx: -1, dy: -1).integral
            let glyphPixels = local.intersection(CGRect(x: 0, y: 0, width: buttonPixels.width, height: buttonPixels.height))
            XCTAssertGreaterThan(glyphPixels.width, 0, file: file, line: line)
            XCTAssertGreaterThan(glyphPixels.height, 0, file: file, line: line)
            frame = CGRect(x: app.frame.minX + (control.minX + glyphPixels.minX) / scaleX,
                           y: app.frame.minY + (control.minY + glyphPixels.minY) / scaleY,
                           width: glyphPixels.width / scaleX, height: glyphPixels.height / scaleY)
            print("LEG132_PIXEL_TEXT_ROI style=\(style) text=\(text) control=\(control) glyphPixels=\(glyphPixels)")
        } else {
            frame = element.frame.insetBy(dx: element.frame.width * 0.12, dy: element.frame.height * 0.27)
        }
        let pixels = CGRect(x: (frame.minX - app.frame.minX) * scaleX, y: (frame.minY - app.frame.minY) * scaleY,
                            width: frame.width * scaleX, height: frame.height * scaleY).integral
        guard let crop = source.cropping(to: pixels) else { XCTFail("No visible label crop: \(style)", file: file, line: line); return }
        let width = crop.width, height = crop.height
        var rgba = [UInt8](repeating: 0, count: width * height * 4)
        let rendered = rgba.withUnsafeMutableBytes { bytes -> Bool in
            guard let context = CGContext(data: bytes.baseAddress, width: width, height: height,
                bitsPerComponent: 8, bytesPerRow: width * 4, space: CGColorSpace(name: CGColorSpace.sRGB)!,
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
            context.draw(crop, in: CGRect(x: 0, y: 0, width: width, height: height)); return true
        }
        XCTAssertTrue(rendered, file: file, line: line)
        func linear(_ byte: UInt8) -> Double {
            let value = Double(byte) / 255
            return value <= 0.04045 ? value / 12.92 : pow((value + 0.055) / 1.055, 2.4)
        }
        let luminances = stride(from: 0, to: rgba.count, by: 4).map {
            0.2126 * linear(rgba[$0]) + 0.7152 * linear(rgba[$0 + 1]) + 0.0722 * linear(rgba[$0 + 2])
        }.sorted()
        guard !luminances.isEmpty else { XCTFail("Empty label crop", file: file, line: line); return }
        let bins = Dictionary(grouping: luminances, by: { Int($0 * 128) })
        let backgroundBin = bins.max(by: { $0.value.count < $1.value.count })!.value.sorted()
        let background = backgroundBin[backgroundBin.count / 2]
        if focused {
            // Native TV focus can be neutral pale gray (observed L≈0.598).
            // Reject the old lavender by colour, then compare both roles above;
            // luminance alone cannot distinguish lavender from native gray.
            let backgroundKey = Int(background * 128)
            let offsets = stride(from: 0, to: rgba.count, by: 4).filter {
                let l = 0.2126 * linear(rgba[$0]) + 0.7152 * linear(rgba[$0 + 1]) + 0.0722 * linear(rgba[$0 + 2])
                return Int(l * 128) == backgroundKey
            }
            let channels = (0..<3).map { channel in
                let values = offsets.map { Double(rgba[$0 + channel]) / 255 }.sorted()
                return values[values.count / 2]
            }
            focusedFillSamples[style] = channels
            print("LEG132_FOCUS_PLATTER style=\(style) backgroundLuminance=\(background) rgb=\(channels)")
            // Unchanged pale/neutral thresholds are checked after both roles are captured.
            return
        }
        // Find ink by its difference from the dominant background, including
        // dark ink. Never pre-filter for passing contrast or include the checkmark.
        let ink = luminances.filter { abs($0 - background) > 0.02 }
        let foreground = ink.isEmpty ? background : ink[ink.count / 2]
        let ratio = (max(foreground, background) + 0.05) / (min(foreground, background) + 0.05)
        print("LEG132_PIXEL_CONTRAST style=\(style) id=\(element.identifier) text=\(text ?? "") ratio=\(ratio) backgroundLuminance=\(background) inkLuminance=\(foreground) inkPixels=\(ink.count) sampledPixels=\(luminances.count)")
        XCTAssertGreaterThanOrEqual(ink.count, max(12, luminances.count / 500), "A meaningful population of resting text ink must be visible: \(style)", file: file, line: line)
        XCTAssertGreaterThanOrEqual(ratio, 4.5, "Actual resting label contrast: \(style)", file: file, line: line)
    }

    private func assertTVLandingVisible(_ card: XCUIElement, app: XCUIApplication, file: StaticString = #filePath, line: UInt = #line) {
        let measuredViewport = app.descendants(matching: .any).matching(identifier: "catalogViewport").firstMatch
        XCTAssertTrue(measuredViewport.exists, "The fixture-only probe must expose the actual catalog GeometryReader viewport", file: file, line: line)
        let viewport = measuredViewport.frame.intersection(app.frame)
        let frame = card.frame
        XCTAssertGreaterThan(viewport.height, 0, file: file, line: line)
        if frame.height <= viewport.height {
            XCTAssertTrue(viewport.contains(frame), "A fitting focused card must be completely inside the actual catalog viewport", file: file, line: line)
        } else {
            // Native focus grows the poster around its centre. Accept that
            // small visual outset while requiring the tall card's top to land
            // at the viewport top, rather than centring inaccessible content.
            let focusOutset = max(12, frame.height * 0.025)
            XCTAssertEqual(frame.minY, viewport.minY, accuracy: focusOutset, "A card taller than the viewport must display from its top", file: file, line: line)
            XCTAssertGreaterThan(frame.intersection(viewport).height, viewport.height * 0.8, file: file, line: line)
            XCTAssertGreaterThanOrEqual(frame.minX, viewport.minX, file: file, line: line)
            XCTAssertLessThanOrEqual(frame.maxX, viewport.maxX, file: file, line: line)
            print("LEG132_OVERSIZED_CARD_TOP frame=\(frame) viewport=\(viewport) nativeFocusOutset=\(focusOutset)")
        }
    }

    private func ownsNativeFocus(_ element: XCUIElement, app: XCUIApplication) -> Bool {
        guard element.exists else { return false }
        if element.hasFocus { return true }
        func focused(_ node: XCUIElementSnapshot) -> XCUIElementSnapshot? {
            node.children.compactMap(focused).first ?? (node.hasFocus ? node : nil)
        }
        if (try? element.snapshot()).flatMap(focused) != nil { return true }
        guard let owner = (try? app.snapshot()).flatMap(focused) else { return false }
        let target = element.frame
        // Form's native row owns focus around its contained switch/button.
        // Match the same exact-target geometry as focus(), including its bound.
        return !target.isEmpty && owner.frame.contains(CGPoint(x: target.midX, y: target.midY))
            && owner.frame.height < max(1, target.height) * 3
    }

    private func focus(_ element: XCUIElement, app: XCUIApplication) {
        // A native large-text grid may have fewer than one precomposed row
        // below the viewport. Actual Down inputs compose those lazy cards.
        for _ in 0..<12 {
            if element.exists { break }
            XCUIRemote.shared.press(.down)
        }
        var previousFrame: CGRect?
        var previousDirection: XCUIRemote.Button?
        func focused(_ node: XCUIElementSnapshot) -> XCUIElementSnapshot? {
            node.children.compactMap(focused).first ?? (node.hasFocus ? node : nil)
        }
        for _ in 0..<30 {
            if element.hasFocus || (try? element.snapshot()).flatMap(focused) != nil { return }
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
    private func activateSettingsChrome(_ app: XCUIApplication) {
        // Native top/bottom tabs use Buttons; an expanded sidebar uses Cells.
        // Same-frame parent/child AX wrappers represent one visible control.
        let query = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", "Settings"))
        #if os(tvOS)
        func ownedVisibleNativeTab(_ element: XCUIElement) -> Bool {
            let window = app.windows.firstMatch.frame, target = element.frame
            guard !target.isEmpty && window.contains(target) else { return false }
            return app.tabBars.allElementsBoundByIndex.contains { bar in
                !bar.frame.isEmpty && window.contains(bar.frame) && bar.frame.contains(target)
                    && bar.descendants(matching: .any).matching(NSPredicate(format: "label == %@ AND identifier == %@", element.label, element.identifier))
                        .allElementsBoundByIndex.contains(where: { $0.frame == target && ($0.elementType == .button || $0.elementType == .cell) })
            }
        }
        // Native top tabs can collapse while content owns focus. Reveal only
        // that actual native bar with a bounded eight remote Up inputs.
        for _ in 0..<8 {
            if query.allElementsBoundByIndex.contains(where: {
                ($0.elementType == .button || $0.elementType == .cell) && ownedVisibleNativeTab($0)
            }) { break }
            XCUIRemote.shared.press(.up)
        }
        #endif
        let exists = query.firstMatch.waitForExistence(timeout: 15)
        let window = app.windows.firstMatch.frame
        let candidates = query.allElementsBoundByIndex.filter { $0.elementType == .button || $0.elementType == .cell }
        var uniqueVisible: [XCUIElement] = []
        for candidate in candidates {
            let frame = candidate.frame
            #if os(tvOS)
            let visible = ownedVisibleNativeTab(candidate)
            #else
            let visible = candidate.isHittable && !frame.isEmpty && window.contains(frame)
            #endif
            print("LEG132_SETTINGS_CHROME role=\(candidate.elementType.rawValue) id=\(candidate.identifier) frame=\(frame) hittable=\(candidate.isHittable) focused=\(candidate.hasFocus) selected=\(candidate.isSelected) fullyVisible=\(visible)")
            guard visible else { continue }
            if let duplicate = uniqueVisible.firstIndex(where: {
                $0.frame == frame && $0.identifier == candidate.identifier && $0.label == candidate.label
            }) { uniqueVisible[duplicate] = candidate }
            else { uniqueVisible.append(candidate) }
        }
        capture("132 Settings navigation chrome before resolution", app: app)
        XCTAssertTrue(exists, "The native Settings navigation control must exist")
        XCTAssertEqual(uniqueVisible.count, 1, "Choose exactly one visible native Settings tab/sidebar control, excluding identical AX wrappers")
        guard uniqueVisible.count == 1, let settings = uniqueVisible.first else { return }
        XCTAssertEqual(settings.label, "Settings")
        XCTAssertTrue(settings.elementType == .button || settings.elementType == .cell)
        #if os(tvOS)
        XCTAssertTrue(ownedVisibleNativeTab(settings), "The remote Settings target must belong to the actual visible native tab bar")
        focus(settings, app: app)
        let bitrate = app.descendants(matching: .any).matching(identifier: "maxBitrate").firstMatch
        XCTAssertTrue(bitrate.waitForExistence(timeout: 15), "Real native remote focus must reach Settings content")
        XCUIRemote.shared.press(.down)
        focus(bitrate, app: app)
        capture("132 native Settings content focus before AA", app: app)
        XCTAssertTrue(ownsNativeFocus(bitrate, app: app), "Measure Settings only after real focus leaves the dimming native tab bar")
        #else
        XCTAssertTrue(settings.isHittable && !settings.frame.isEmpty && window.contains(settings.frame), "The actual Settings control must be hittable and fully inside the app window")
        settings.tap()
        #endif

    }
    #if os(iOS)
    private func assertSingleLineTouchHeight(_ element: XCUIElement) {
        // AX geometry uses floating-point coordinates; this tolerance is far below a physical pixel.
        XCTAssertGreaterThanOrEqual(element.frame.height + 0.000_001, 48, "\(element.identifier) must keep its true 48 pt touch target")
        XCTAssertLessThanOrEqual(element.frame.height - 0.000_001, 52, "\(element.identifier) must not add native border padding around a 48 pt label")
    }
    /// A target may exist in an offscreen lazy row. Captures need the complete
    /// rendered action below navigation chrome, not just an existing AX node.
    private func reveal(_ element: XCUIElement, app: XCUIApplication) {
        XCTAssertTrue(element.waitForExistence(timeout: 15))
        // Anonymous headings have a label but no identifier. Matching the empty
        // identifier would select unrelated nested horizontal season scrollers.
        let identifier = element.identifier
        let identity = identifier.isEmpty ? element.label : identifier
        XCTAssertFalse(identity.isEmpty, "A reveal target needs an accessibility identifier or label")
        let targetMatch = NSPredicate(format: identifier.isEmpty ? "label == %@" : "identifier == %@", identity)
        for _ in 0..<16 {
            let window = app.windows.firstMatch.frame
            let target = element.frame
            let containers = (app.scrollViews.allElementsBoundByIndex + app.collectionViews.allElementsBoundByIndex)
                .filter { $0.isHittable && $0.frame.intersects(window) }
            let containing = containers.filter { $0.descendants(matching: .any).matching(targetMatch).firstMatch.exists }
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
    private let pageSize: Int
    init(pageSize: Int = 6) throws {
        precondition(pageSize > 0)
        self.pageSize = pageSize
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
                let all = [series()] + (1..<(pageSize * 2)).map { movie("catalog-\($0)") }
                body = ["Items": Array(all.dropFirst(start).prefix(pageSize)), "TotalRecordCount": all.count]
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
