// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import XCTest
import AVFoundation
import PictureshowCore
#if os(tvOS)
@testable import Pictureshow_tvOS
#else
@testable import Pictureshow_iOS
#endif

/// Ordinary negotiation/extras responses feed the production session. No UI labels,
/// speed values or countdown verdicts are supplied by this transport fixture.
private final class PresentationProtocol: URLProtocol {
    static var conversion = true
    static var credits = false
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let path = request.url!.path
        let item: [String: Any] = ["Id": "presentation", "Name": "Presentation episode", "Type": "Episode", "SeriesId": "series", "ParentIndexNumber": 1, "IndexNumber": 1, "RunTimeTicks": 1_200_000_000, "Chapters": []]
        let next: [String: Any] = ["Id": "following", "Name": "Following episode", "Type": "Episode", "SeriesId": "series", "ParentIndexNumber": 1, "IndexNumber": 2]
        var response: Any = [:] as [String: String]
        if path.hasSuffix("PlaybackInfo") {
            var source: [String: Any] = ["Id": "presentation-source", "Container": "matroska", "Bitrate": 8_000_000,
                "SupportsDirectPlay": !Self.conversion, "SupportsDirectStream": false,
                "DefaultAudioStreamIndex": 1, "DefaultSubtitleStreamIndex": -1,
                "MediaStreams": [["Index": 0, "Type": "Video", "Codec": "hevc", "Width": 1920, "Height": 1080], ["Index": 1, "Type": "Audio", "Codec": "aac"]]]
            if Self.conversion {
                var components = URLComponents(string: "https://media.example.test/Videos/presentation/stream")!
                components.queryItems = [URLQueryItem(name: "VideoCodec", value: "h264"), URLQueryItem(name: "AudioCodec", value: "aac"), URLQueryItem(name: "TranscodeReasons", value: "VideoCodecNotSupported,ContainerBitrateExceedsLimit,https://private.example.test/secret-path?token=private-value")]
                source["TranscodingUrl"] = components.url!.absoluteString
            }
            response = ["MediaSources": [source], "PlaySessionId": "presentation-session"]
        } else if path.hasSuffix("/Episodes") {
            response = ["Items": [item, next]]
        } else if path.contains("MediaSegments") {
            response = ["Items": Self.credits ? [["Id": "credits", "Type": "Outro", "StartTicks": 1_000_000_000, "EndTicks": 1_200_000_000]] : []]
        } else if path.hasSuffix("/Items/presentation") { response = item }
        let data = try! JSONSerialization.data(withJSONObject: response)
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "application/json"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: data)
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() { }
}
private final class PresentationInertPlayer: AVPlayer {
    override func replaceCurrentItem(with item: AVPlayerItem?) { }
}

@MainActor final class PlayerPresentationTests: XCTestCase {
    func testCaptionExcludesOverlayCardWithoutCollapsingNarrowVideo() {
        let overlay = CGRect(x: 0, y: 0, width: 874, height: 402)
        let portraitVideo = CGRect(x: 324, y: 0, width: 226, height: 402)
        let narrow = PictureshowPlayerViewController.captionHorizontalRange(video: portraitVideo, overlay: overlay, trailingExclusion: 360)
        XCTAssertGreaterThan(narrow.upperBound - narrow.lowerBound, 150, "A portrait video keeps readable caption width; the card already sits mostly beside it")
        XCTAssertLessThanOrEqual(narrow.upperBound, 514, "Captions end before the actual overlay card band")
        let wide = PictureshowPlayerViewController.captionHorizontalRange(video: overlay, overlay: overlay, trailingExclusion: 360)
        XCTAssertGreaterThan(wide.upperBound - wide.lowerBound, 400)
        XCTAssertLessThanOrEqual(wide.upperBound, 514)
        let noCard = PictureshowPlayerViewController.captionHorizontalRange(video: portraitVideo, overlay: overlay, trailingExclusion: 0)
        XCTAssertEqual(noCard.upperBound - noCard.lowerBound, 203.4, accuracy: 0.1)
    }

    func testCaptionStopsBeforeMeasuredCardAcrossSafeAreaCoordinateOrigins() {
        // Landscape native overlay starts 47 points inside the hosting window.
        // The actual card starts at 468.67; its measured edge must be converted.
        let overlay = CGRect(x: 0, y: 0, width: 780, height: 402)
        let video = CGRect(x: 0, y: 0, width: 780, height: 402)
        let range = PictureshowPlayerViewController.captionHorizontalRange(video: video, overlay: overlay, trailingExclusion: 0, excludedLeadingEdge: 468.67 - 47)
        XCTAssertLessThanOrEqual(range.upperBound + 47, 468.67 - 12)
        XCTAssertGreaterThan(range.upperBound - range.lowerBound, 300)
    }

    #if os(iOS)
    func testCaptionKeepsCenteredWidthWhenCardIsAboveItsActualVerticalBand() {
        let video = CGRect(x: 0, y: 0, width: 874, height: 402)
        let horizontal: ClosedRange<CGFloat> = 43.7...830.3
        let frame = PictureshowPlayerViewController.captionFrame(video: video, horizontal: horizontal, bottom: 216, obstacles: [CGRect(x: 468, y: 12, width: 324, height: 140)]) { _ in 34 }
        XCTAssertEqual(frame.width, 786.6, accuracy: 0.1)
        XCTAssertEqual(frame.midX, video.midX, accuracy: 0.1)
        XCTAssertEqual(frame.minY, 182, accuracy: 0.1)
    }
    func testCaptionUsesActualOverlayRectanglesAndReservesNoticeGap() {
        let video = CGRect(x: 0, y: 0, width: 874, height: 402)
        let notice = CGRect(x: 82, y: 12, width: 710, height: 99)
        let frame = PictureshowPlayerViewController.captionFrame(video: video, horizontal: 43.7...830.3, bottom: 180, obstacles: [notice]) { _ in 70 }
        XCTAssertGreaterThanOrEqual(frame.minY, notice.maxY + 12)
        XCTAssertFalse(frame.intersects(notice))
        XCTAssertLessThanOrEqual(frame.maxY, 180)
        XCTAssertGreaterThan(frame.height, 30)
    }
    func testCaptionClearsAllObstaclesWhenNarrowingWouldRewrapUnderEarlierNotice() {
        let video = CGRect(x: 0, y: 0, width: 874, height: 402)
        let notice = CGRect(x: 0, y: 0, width: 874, height: 130)
        let card = CGRect(x: 450, y: 200, width: 424, height: 110)
        let frame = PictureshowPlayerViewController.captionFrame(video: video, horizontal: 43.7...830.3, bottom: 300, obstacles: [notice, card]) { $0 < 600 ? 200 : 34 }
        XCTAssertFalse(frame.intersects(notice.insetBy(dx: -12, dy: -12)))
        XCTAssertFalse(frame.intersects(card.insetBy(dx: -12, dy: -12)))
        XCTAssertEqual(frame.width, 786.6, accuracy: 0.1, "A full-width slot above the card preserves one-line text")
        XCTAssertEqual(frame.height, 34, accuracy: 0.1)
    }
    #endif
    private func session(conversion: Bool = true, credits: Bool = false, notice: Bool = true) async throws -> PlaybackSession {
        PresentationProtocol.conversion = conversion; PresentationProtocol.credits = credits
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [PresentationProtocol.self]
        let client = JellyfinClient(deviceID: "presentation-device", session: URLSession(configuration: configuration))
        let account = Account(server: Server(url: URL(string: "https://media.example.test")!), userId: "presentation-viewer", username: "Presentation viewer", accessToken: "private-value", deviceID: "presentation-device")
        var item = MediaItem(id: "presentation", name: "Presentation episode", type: "Episode", runTimeTicks: 1_200_000_000)
        item.seriesId = "series"; item.indexNumber = 1; item.parentIndexNumber = 1
        var preferences = PlaybackPreferences(); preferences.bitrateMbps = 12; preferences.showConversionNotice = notice
        let session = PlaybackSession(client: client, account: account, item: item, preferences: preferences, player: PresentationInertPlayer())
        session.foregrounded()
        try await session.prepare(startTicks: 0, audio: nil, subtitle: -1)
        for _ in 0..<200 {
            if session.nextItem != nil { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTAssertEqual(session.nextItem?.id, "following", "The next card must use the production chronological lookup")
        _ = session.nativePlayRequested()
        addTeardownBlock { await session.stop() }
        return session
    }
    func testConversionNoticeDefersUntilReadyAndFitsThenConsumesOncePerViewing() {
        let notice = ConversionNoticePresentation()
        notice.update(itemID: "first", qualifies: true, ready: false, fits: true)
        XCTAssertFalse(notice.active, "Preparing playback cannot consume the brief notice")
        notice.update(itemID: "first", qualifies: true, ready: true, fits: false)
        XCTAssertFalse(notice.active, "A short landscape control panel must leave the notice pending until there is room")
        notice.update(itemID: "first", qualifies: true, ready: true, fits: true)
        XCTAssertTrue(notice.active)
        notice.close()
        notice.update(itemID: "first", qualifies: true, ready: true, fits: true)
        XCTAssertFalse(notice.active, "Panel closure, a seek or a replacement within the viewing must not replay a consumed notice")
        notice.update(itemID: "next", qualifies: true, ready: true, fits: true)
        XCTAssertTrue(notice.active, "A new viewing receives its own notice opportunity")
        notice.update(itemID: "next", qualifies: false, ready: true, fits: true)
        XCTAssertFalse(notice.active, "A direct-compatible replacement removes stale conversion guidance")
        notice.update(itemID: "next", qualifies: true, ready: true, fits: true)
        XCTAssertFalse(notice.active, "A same-viewing source change cannot restart the notice clock")
        notice.close()
    }
    func testPlaybackInfoUsesCoreReasonsAndSeparatesReceivedFormatsFromServerMetadata() async throws {
        let session = try await session()
        XCTAssertEqual(session.playbackInfoReasons.count, 3)
        XCTAssertTrue(session.playbackInfoReasons[0].contains("cannot decode this video format"))
        XCTAssertTrue(session.playbackInfoReasons[1].contains("12 Mbps"))
        XCTAssertTrue(session.playbackInfoReasons[2].contains("does not recognize"))
        let source = try XCTUnwrap(session.playbackInfoSections.first { $0.id == "Server-reported source" })
        XCTAssertEqual(source.rows.first { $0.id == "Video codec" }?.value, "HEVC")
        XCTAssertEqual(source.rows.first { $0.id == "Resolution" }?.value, "1920 × 1080")
        XCTAssertEqual(source.rows.first { $0.id == "Bitrate" }?.value, "8.00 Mbps")
        let received = try XCTUnwrap(session.playbackInfoSections.first { $0.id == "Received by player" })
        XCTAssertTrue(received.rows.allSatisfy { $0.value == "Not reported" }, "An inert player has observed no output; negotiation H264/AAC candidates must not be claimed as received")
        let visible = (session.playbackInfoReasons + session.playbackInfoSections.flatMap { $0.rows.map(\.value) }).joined(separator: " ")
        for privateText in ["https://", "private.example", "secret-path", "private-value", "token=", "/Videos/"] {
            XCTAssertFalse(visible.contains(privateText), "Playback info must not expose raw server reason text or media credentials")
        }
    }
    func testConversionNoticeUsesCoreFixAndPersistedPreference() async throws {
        let session = try await session()
        let notice = try XCTUnwrap(session.conversionNotice)
        XCTAssertTrue(notice.reason.contains("video format cannot play directly"))
        XCTAssertEqual(notice.fix, "A 1080p H.264 copy on the server would play without conversion.")
        let suppressed = try await self.session(notice: false)
        XCTAssertNil(suppressed.conversionNotice)
        let direct = try await self.session(conversion: false)
        XCTAssertNil(direct.conversionNotice)
        XCTAssertTrue(direct.playbackInfoReasons.isEmpty)
    }
    func testRateAwareNextCardKeepsManualActionsButHidesSuspendedNumber() async throws {
        let session = try await session()
        let now = Date().addingTimeInterval(5)
        XCTAssertTrue(session.selectPlaybackSpeed(2))
        session.updateNextCard(elapsed: 0, now: now, reader: false, durationSeconds: 120, mediaPositionTicks: Ticks.from(seconds: 115), preparing: false)
        XCTAssertTrue(session.nextCardVisible)
        XCTAssertEqual(session.nextCountdown, 3, "Five media seconds at 2× take three displayed wall seconds after rounding up")
        session.panelOpen = true
        session.updateNextCard(elapsed: 1, now: now, reader: false, durationSeconds: 120, mediaPositionTicks: Ticks.from(seconds: 115), preparing: false)
        XCTAssertTrue(session.nextCardVisible); XCTAssertNil(session.nextCountdown)
        session.panelOpen = false
        session.updateNextCard(elapsed: 1, now: now, reader: true, durationSeconds: 120, mediaPositionTicks: Ticks.from(seconds: 115), preparing: false)
        XCTAssertTrue(session.nextCardVisible); XCTAssertNil(session.nextCountdown)
        XCTAssertTrue(session.selectPlaybackSpeed(0.5))
        session.updateNextCard(elapsed: 0, now: now, reader: false, durationSeconds: 120, mediaPositionTicks: Ticks.from(seconds: 115), preparing: false)
        XCTAssertEqual(session.nextCountdown, 10)
        session.backgrounded(); session.foregrounded()
        session.updateNextCard(elapsed: 1, now: now, reader: false, durationSeconds: 120, mediaPositionTicks: Ticks.from(seconds: 115), preparing: false)
        XCTAssertTrue(session.nextCardVisible); XCTAssertNil(session.nextCountdown, "Foregrounding preserves pause intent and requires Play")
        session.cancelContinuation()
        session.updateNextCard(elapsed: 1, now: now, reader: false, durationSeconds: 120, mediaPositionTicks: Ticks.from(seconds: 115), preparing: false)
        XCTAssertFalse(session.nextCardVisible); XCTAssertNil(session.nextCountdown)
    }
    func testCreditsCountdownCountsWallSecondsAndKeepsRateAcrossInternalPrepare() async throws {
        let session = try await session(credits: true)
        let now = Date().addingTimeInterval(5)
        XCTAssertTrue(session.selectPlaybackSpeed(1.75))
        for _ in 0..<4 {
            session.updateNextCard(elapsed: 1, now: now, reader: false, durationSeconds: 120, mediaPositionTicks: Ticks.from(seconds: 105), preparing: false)
        }
        XCTAssertEqual(session.nextCountdown, 6, "Credits wait ten wall seconds; speed must not multiply the elapsed credits budget")
        try await session.prepare(startTicks: Ticks.from(seconds: 105), audio: nil, subtitle: -1)
        XCTAssertEqual(session.playbackRate, 1.75)
        XCTAssertEqual(session.player.defaultRate, 1.75)
        XCTAssertFalse(session.selectPlaybackSpeed(2, encodedOutput: true))
        XCTAssertEqual(session.playbackRate, 1)
        XCTAssertEqual(session.speedLimitation, PlaybackSpeeds.unsupportedOutputNotice)
        await session.advance()
        XCTAssertEqual(session.item.id, "following", "The warning reset must be observed across an actual viewing transition")
        XCTAssertEqual(session.playbackRate, 1)
        XCTAssertEqual(session.player.defaultRate, 1)
        XCTAssertNil(session.speedLimitation, "A previous title's output limitation cannot follow the new viewing after its speed resets")
    }
    #if os(tvOS)
    func testTVContinuationRetainsActionsWhenPausedNumberDisappears() async throws {
        let session = try await session()
        let next = try XCTUnwrap(session.nextItem)
        let coordinator = NativePlayer.Coordinator(session: session, close: {})
        coordinator.updateContinuation(next, countdown: 4, visible: true)
        let play = try XCTUnwrap(coordinator.continuationContextActions.first)
        let cancel = try XCTUnwrap(coordinator.continuationContextActions.last)
        coordinator.updateContinuation(next, countdown: nil, visible: true)
        XCTAssertTrue(coordinator.continuationContextActions.first === play)
        XCTAssertTrue(coordinator.continuationContextActions.last === cancel)
        XCTAssertEqual(play.title, "Play now")
        XCTAssertEqual(cancel.title, "Cancel")
        XCTAssertFalse(coordinator.continuationTransportActions.first!.title.contains("("))
        coordinator.updateContinuation(next, countdown: nil, visible: false)
        XCTAssertTrue(coordinator.continuationContextActions.isEmpty)
        XCTAssertTrue(coordinator.continuationTransportActions.isEmpty)
    }
    #endif
}
