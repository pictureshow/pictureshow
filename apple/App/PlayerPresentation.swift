// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import SwiftUI
import AVFoundation
import AVKit
import PictureshowCore

/// Metadata from the selected server source and formats observed on the AVPlayerItem
/// are deliberately separate. Negotiation candidates never stand in for received formats.
struct PlaybackInfoSection: Identifiable, Equatable {
    let id: String
    let rows: [PlaybackInfoRow]
}
struct PlaybackInfoRow: Identifiable, Equatable {
    let id: String
    let value: String
}

extension PlaybackSession {
    var conversionNotice: ConversionNotice? {
        guard preferences.showConversionNotice, let decision else { return nil }
        #if os(tvOS)
        let device = "this TV"
        #else
        let device = UIDevice.current.userInterfaceIdiom == .pad ? "this tablet" : "this phone"
        #endif
        return ConversionNotice.of(decision, subtitleIndex: decision.subtitleIndex ?? -1, device: device)
    }
    var playbackInfoSections: [PlaybackInfoSection] {
        let source = decision?.source
        let video = source?.mediaStreams.first { $0.type == "Video" }
        let audio = source?.mediaStreams.first { $0.type == "Audio" && $0.index == decision?.audioIndex }
        let unknown = "Not reported"
        let received = player.currentItem?.tracks.compactMap { $0.assetTrack }.flatMap { track -> [(String, CMFormatDescription)] in
            (track.formatDescriptions as? [CMFormatDescription] ?? []).map { (track.mediaType.rawValue, $0) }
        } ?? []
        func codec(_ type: AVMediaType) -> String {
            guard let value = received.first(where: { $0.0 == type.rawValue })?.1 else { return unknown }
            let code = CMFormatDescriptionGetMediaSubType(value)
            let bytes: [UInt8] = [UInt8((code >> 24) & 255), UInt8((code >> 16) & 255), UInt8((code >> 8) & 255), UInt8(code & 255)]
            return PictureshowCore.PlaybackInformation.codec(String(bytes: bytes, encoding: .ascii)?.trimmingCharacters(in: .whitespaces))
        }
        let size = player.currentItem?.presentationSize ?? .zero
        let receivedResolution = PictureshowCore.PlaybackInformation.resolution(width: size.width > 0 ? Int(size.width) : nil, height: size.height > 0 ? Int(size.height) : nil)
        return [
            .init(id: "Playback", rows: [
                .init(id: "Method", value: decision.map { PictureshowCore.PlaybackInformation.method($0.method.rawValue, url: $0.url.absoluteString) } ?? unknown),
                .init(id: "Subtitles", value: source.map { PictureshowCore.PlaybackInformation.subtitle(source: $0, selectedIndex: decision?.subtitleIndex ?? -1) } ?? unknown)]),
            .init(id: "Server-reported source", rows: [
                .init(id: "Container", value: PictureshowCore.PlaybackInformation.container(source?.container)),
                .init(id: "Video codec", value: PictureshowCore.PlaybackInformation.codec(video?.codec)),
                .init(id: "Audio codec", value: PictureshowCore.PlaybackInformation.codec(audio?.codec)),
                .init(id: "Bitrate", value: PictureshowCore.PlaybackInformation.bitrate(source?.bitrate)),
                .init(id: "Resolution", value: PictureshowCore.PlaybackInformation.resolution(width: video?.width, height: video?.height))]),
            .init(id: "Received by player", rows: [
                .init(id: "Video codec", value: codec(.video)), .init(id: "Audio codec", value: codec(.audio)),
                .init(id: "Resolution", value: receivedResolution)])]
    }
    var playbackInfoReasons: [String] {
        guard let decision else { return [] }
        return PictureshowCore.PlaybackInformation.reasons(decision.transcodeReasons, method: decision.method.rawValue, qualityMbps: preferences.bitrateMbps)
    }
}

struct PlaybackInfoRows: View {
    @ObservedObject var session: PlaybackSession
    var body: some View {
        if !session.playbackInfoReasons.isEmpty {
            Section("Why the server is converting") {
                ForEach(Array(session.playbackInfoReasons.enumerated()), id: \.offset) { index, reason in
                    Text(reason).fixedSize(horizontal: false, vertical: true)
                        .accessibilityIdentifier("conversionReason-\(index)")
                        .readablePlayerRow()
                }
            }
        }
        ForEach(session.playbackInfoSections) { section in
            Section(section.id) {
                ForEach(section.rows) { row in
                    VStack(alignment: .leading, spacing: 4) {
                        Text(row.id).font(.caption).foregroundStyle(Theme.muted)
                        Text(row.value).fixedSize(horizontal: false, vertical: true)
                    }.accessibilityElement(children: .combine)
                        .accessibilityIdentifier("playbackInfo-\(section.id)-\(row.id)")
                        .readablePlayerRow()
                }
            }
        }
    }
}
private extension View {
    @ViewBuilder func readablePlayerRow() -> some View {
        #if os(tvOS)
        self.focusable().padding(.vertical, 6)
        #else
        self
        #endif
    }
}

struct SpeedOptions: View {
    @ObservedObject var session: PlaybackSession
    let done: () -> Void
    @Environment(\.verticalSizeClass) private var verticalSize
    @State private var compactRate: Double = 1
    @FocusState private var selectedRate: Double?
    @AccessibilityFocusState private var readerRate: Double?
    var body: some View {
        Section {
            #if os(iOS)
            if verticalSize == .compact {
                Picker("Speed", selection: $compactRate) {
                    ForEach(PlaybackSpeeds.choices, id: \.self) { rate in
                        Text(PlaybackSpeeds.label(rate)).tag(rate)
                            .accessibilityIdentifier("speed-\(PlaybackSpeeds.label(rate))")
                    }
                }.pickerStyle(.segmented).frame(minHeight: 48)
                    .accessibilityIdentifier("playbackSpeedSegments")
                    .onAppear { compactRate = session.playbackRate }
                    .onChange(of: compactRate) { _, rate in
                        guard rate != session.playbackRate else { return }
                        if session.selectPlaybackSpeed(rate) { done() }
                    }
                    .onChange(of: session.playbackRate) { _, rate in compactRate = rate }
            } else { rateList }
            #else
            rateList
            #endif
        } footer: {
            if let warning = session.speedLimitation ?? session.playbackSpeedWarning {
                Text(warning).fixedSize(horizontal: false, vertical: true).readablePlayerRow()
                    .accessibilityIdentifier("speedWarning")
            }
        }.onAppear { selectedRate = session.playbackRate; readerRate = session.playbackRate }
    }
    private var rateList: some View { ForEach(PlaybackSpeeds.choices, id: \.self) { rateButton($0) } }
    private func rateButton(_ rate: Double) -> some View {
        Button {
            if session.selectPlaybackSpeed(rate) { done() }
        } label: {
            HStack {
                Text(PlaybackSpeeds.label(rate)).fixedSize()
                if verticalSize != .compact { Spacer() }
                if session.playbackRate == rate {
                    #if os(tvOS)
                    Image(systemName: "checkmark").foregroundStyle(.primary).accessibilityHidden(true)
                    #else
                    Image(systemName: "checkmark").foregroundStyle(Theme.accent).accessibilityHidden(true)
                    #endif
                }
            }
            #if os(iOS)
            .foregroundStyle(Color.primary)
            #else
            .foregroundStyle(.primary)
            #endif
        }
        .accessibilityLabel(session.playbackRate == rate && session.speedLimitation != nil ? "\(PlaybackSpeeds.label(rate)). \(session.speedLimitation!)" : PlaybackSpeeds.label(rate))
        .accessibilityValue(session.playbackRate == rate ? "Selected" : "")
        .accessibilityHint(session.speedLimitation ?? "")
        .accessibilityIdentifier("speed-\(PlaybackSpeeds.label(rate))")
        .focused($selectedRate, equals: rate).accessibilityFocused($readerRate, equals: rate)
    }
}

/// One short-lived presentation per viewing; suppression never restarts its clock.
@MainActor final class ConversionNoticePresentation: ObservableObject {
    @Published private(set) var active = false
    private var itemID: String?
    private var consumed = false
    private var announced = false
    private var expiry: Task<Void, Never>?
    func update(itemID: String, qualifies: Bool, ready: Bool, fits: Bool) {
        if self.itemID != itemID {
            expiry?.cancel(); self.itemID = itemID; consumed = false; announced = false; active = false
        }
        guard qualifies else { active = false; return }
        guard !consumed, ready, fits else { return }
        consumed = true; active = true
        expiry = Task { [weak self] in
            do { try await Task.sleep(for: .seconds(10)) } catch { return }
            self?.active = false
        }
    }
    func consumeAnnouncement() -> Bool {
        guard active, !announced else { return false }
        announced = true; return true
    }
    func close() { expiry?.cancel(); expiry = nil; active = false }
}

struct ConversionNoticeBanner: View {
    @ObservedObject var session: PlaybackSession
    @ObservedObject var presentation: ConversionNoticePresentation
    let fits: Bool
    let obscured: Bool
    let openInfo: () -> Void
    private var signature: String { "\(session.item.id)|\(session.conversionNotice != nil)|\(session.waitingForVideo)|\(fits)|\(obscured)" }
    var body: some View {
        // Keep the observer mounted while the notice is pending. Group forwards
        // modifiers to its children; with no active notice it has no child to
        // observe the READY/fit change that is supposed to reveal that notice.
        ZStack(alignment: .topLeading) {
            if presentation.active, fits, !obscured, let notice = session.conversionNotice {
                #if os(tvOS)
                noticeContent(notice)
                #else
                Button(action: openInfo) { noticeContent(notice) }.buttonStyle(.plain).accessibilityHint("Opens Playback info with details and fixes")
                #endif
            }
        }
        .onChange(of: signature, initial: true) { _, _ in
            presentation.update(itemID: session.item.id, qualifies: session.conversionNotice != nil,
                ready: !session.waitingForVideo && !obscured, fits: fits)
        }

    }
    private func noticeContent(_ notice: ConversionNotice) -> some View {
        #if os(tvOS)
        let path = "Open the player controls, then choose Playback info."
        #else
        let path = ""
        #endif
        return HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 6) {
                Text(notice.title).font(.headline)
                Text("\(notice.reason) \(notice.fix) \(path)").font(.callout)
            }
            #if os(iOS)
            Image(systemName: "chevron.right").accessibilityHidden(true)
            #endif
        }.fixedSize(horizontal: false, vertical: true).padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Theme.surface.opacity(0.96), in: RoundedRectangle(cornerRadius: 12))
            .foregroundStyle(.white)
            .accessibilityElement(children: .combine).accessibilityIdentifier("conversionNotice")
            .measureTouchCaptionObstacle("notice")
            .onAppear {
                if UIAccessibility.isVoiceOverRunning, presentation.consumeAnnouncement() {
                    let text = NSAttributedString(string: "\(notice.title). \(notice.reason) \(notice.fix) \(path)", attributes: [.accessibilitySpeechQueueAnnouncement: true])
                    UIAccessibility.post(notification: .announcement, argument: text)
                }
            }
    }
}

#if os(iOS)
/// SwiftUI owns touch controls so panels, scrubbing, reader state and foregrounding
/// feed the same viewing's countdown and idle policy without guessing at AVKit state.
struct TouchPlayer: View {
    @ObservedObject var session: PlaybackSession
    @ObservedObject var noticePresentation: ConversionNoticePresentation
    @Binding var panel: String?
    let finish: () -> Void
    let tracks: () -> Void
    @Environment(\.scenePhase) private var phase
    @Environment(\.dynamicTypeSize) private var typeSize
    @State private var voiceOver = UIAccessibility.isVoiceOverRunning
    @State private var interaction = 0
    @State private var scrubbing = false
    @State private var preview: Double = 0
    @State private var originalOrientation: UIInterfaceOrientationMask?
    @State private var orientationSettled = false
    @State private var controlsHeight: CGFloat = 0
    @State private var captionObstacleFrames: [String: CGRect] = [:]
    @State private var portraitNoticeMeasuredHeight: CGFloat = 0
    @State private var anchoredBelowNotice = false
    @State private var reservedNoticeHeight: CGFloat = 0
    @State private var orientationMessage: String?
    @State private var opener: String?
    @AccessibilityFocusState private var readerFocus: String?

    private var idleKey: String { "\(interaction)|\(session.isPaused)|\(session.waitingForVideo)|\(panel ?? "")|\(scrubbing)|\(voiceOver)|\(session.controlsVisible)|\(session.panelOpen)" }
    var body: some View {
        GeometryReader { geometry in
            let portrait = geometry.size.height > geometry.size.width
            let tablet = min(geometry.size.width, geometry.size.height) >= 600
            let aspect = session.player.currentItem?.presentationSize ?? .zero
            let ratio = aspect.width > 0 && aspect.height > 0 ? aspect.width / aspect.height : 16 / 9
            let videoHeight = portrait ? min(geometry.size.width / ratio, geometry.size.height * 0.45) : geometry.size.height
            let compact = !portrait && session.nextCardVisible
            let nativeVideoWidth = min(geometry.size.width + geometry.safeAreaInsets.leading + geometry.safeAreaInsets.trailing,
                                       (geometry.size.height + geometry.safeAreaInsets.top + geometry.safeAreaInsets.bottom) * ratio)
            let subtitleWidth = (portrait ? geometry.size.width : nativeVideoWidth) * 0.9
            let portraitVideoWidth = min(geometry.size.width, videoHeight * ratio)
            ZStack(alignment: .top) {
                // An overlay lets the video expand behind system insets without
                // widening or vertically centering the sibling control layout.
                Color.clear.frame(width: geometry.size.width, height: videoHeight)
                    .overlay(alignment: .top) {
                NativePlayer(session: session, close: finish, didAppear: {
                    updateOrientation(tablet: tablet)
                    if tablet || !portrait { orientationSettled = true }
                }, touchControlsHeight: portrait ? 0 : session.controlsVisible ? controlsHeight + 24 : 0,
                    touchCaptionExclusionFrames: portrait ? captionObstacleFrames["notice"].map { [$0] } ?? [] : Array(captionObstacleFrames.values))
                    // Let the video extend behind cutouts/home indicator while
                    // the sibling controls retain the geometry's safe insets.
                    .frame(width: portrait ? geometry.size.width : geometry.size.width + geometry.safeAreaInsets.leading + geometry.safeAreaInsets.trailing,
                           height: portrait ? videoHeight : geometry.size.height + geometry.safeAreaInsets.top + geometry.safeAreaInsets.bottom)
                    .offset(x: portrait ? 0 : (geometry.safeAreaInsets.trailing - geometry.safeAreaInsets.leading) / 2,
                            y: portrait ? 0 : -geometry.safeAreaInsets.top)
                    }
                Color.clear.contentShape(Rectangle()).onTapGesture { interact(); session.controlsVisible.toggle() }
                    .accessibilityLabel(session.item.name).accessibilityHint("Show playback controls")
                    .accessibilityAddTraits(.isButton).accessibilityIdentifier("playerSurface")
                    .accessibilityAction(named: "Show controls") { showControls() }
                    .accessibilityAction(named: "Back 10 seconds") { session.seek(by: -10); showControls() }
                    .accessibilityAction(named: "Forward 10 seconds") { session.seek(by: 10); showControls() }
                if portrait {
                    VStack(spacing: 0) {
                        Spacer().frame(height: videoHeight)
                        ScrollView {
                            VStack(spacing: 12) {
                                topCards(width: geometry.size.width - 32, height: geometry.size.height - videoHeight, fillColumn: true, subtitleWidth: subtitleWidth, includeNotice: false)
                                    .padding(.horizontal, 16)
                                if session.controlsVisible {
                                    controls(width: geometry.size.width, tablet: tablet, compact: false)
                                        .background(GeometryReader { proxy in Color.clear.preference(key: PlayerControlsHeight.self, value: proxy.size.height) })
                                }
                            }.padding(.horizontal, 20).padding(.vertical, 12)
                        }.frame(maxHeight: .infinity).accessibilityIdentifier("touchPlayerControls")
                    }
                    portraitNotice(videoWidth: portraitVideoWidth, videoHeight: videoHeight)
                        .frame(width: portraitVideoWidth, height: videoHeight, alignment: .topLeading)
                        .frame(maxWidth: .infinity, alignment: .center)
                } else {
                    VStack {
                        Spacer(minLength: 0)
                        if session.controlsVisible {
                            controls(width: geometry.size.width, tablet: tablet, compact: compact)
                                .background(GeometryReader { proxy in Color.clear.preference(key: PlayerControlsHeight.self, value: proxy.size.height) })
                        }
                    }.padding(.horizontal, 20).padding(.vertical, 12)
                    topCards(width: geometry.size.width, height: geometry.size.height, subtitleWidth: subtitleWidth)
                        .frame(maxHeight: max(48, geometry.size.height - (session.controlsVisible ? controlsHeight : 0) - 36), alignment: .top)
                        .padding(.horizontal, 20).padding(.top, 12)
                }
            }.frame(width: geometry.size.width, height: geometry.size.height)
            .onAppear {
                session.controlsVisible = true
            }
            .onChange(of: geometry.size) { _, size in
                portraitNoticeMeasuredHeight = 0
                if min(size.width, size.height) >= 600 || size.width > size.height { orientationSettled = true }
            }
            .onPreferenceChange(CaptionObstacleFrames.self) { frames in
                captionObstacleFrames = frames
                if portrait, let notice = frames["notice"], notice.height.isFinite, notice.height > 0 {
                    // Retain the measured size when suppressed, so a too-tall
                    // card cannot oscillate between estimated fit and no fit.
                    portraitNoticeMeasuredHeight = max(portraitNoticeMeasuredHeight, notice.height)
                }
            }
            .onChange(of: min(geometry.size.width, geometry.size.height) >= 600) { _, tablet in updateOrientation(tablet: tablet) }
        }
        .accessibilityAction(.escape) {
            if panel != nil { panel = nil }
            else if session.controlsVisible {
                session.controlsVisible = false
                UIAccessibility.post(notification: .announcement, argument: "Controls hidden")
            } else { finish() }
        }
        .onPreferenceChange(PlayerControlsHeight.self) { controlsHeight = $0 }
        .onChange(of: typeSize) { _, _ in portraitNoticeMeasuredHeight = 0 }
        .onDisappear { restoreOrientation() }
        .onReceive(NotificationCenter.default.publisher(for: UIAccessibility.voiceOverStatusDidChangeNotification)) { _ in voiceOver = UIAccessibility.isVoiceOverRunning; if voiceOver { showControls() } }
        .onChange(of: panel) { old, new in
            interact()
            if old != nil, new == nil { readerFocus = opener }
        }
        .onChange(of: phase) { _, value in if value == .active { showControls() } }
        .onChange(of: session.isPaused) { _, paused in if paused { showControls() } }
        .task(id: idleKey) {
            guard session.controlsVisible, !session.isPaused, !session.waitingForVideo, panel == nil, !session.panelOpen, !scrubbing, !voiceOver else { return }
            do { try await Task.sleep(for: .seconds(10)) } catch { return }
            session.controlsVisible = false
        }
    }
    private func portraitNotice(videoWidth: CGFloat, videoHeight: CGFloat) -> some View {
        let cardWidth = max(1, min(480, videoWidth - 40))
        let cardHeight = max(noticeHeight(width: cardWidth), portraitNoticeMeasuredHeight)
        let captionHeight = PictureshowPlayerViewController.touchCaptionHeight(text: session.subtitleCue, preferences: session.preferences, width: max(1, videoWidth * 0.9))
        // Match the native caption's preferred bottom band. Reserve its full
        // font size before deciding whether the transient notice can appear.
        let captionInset = session.controlsVisible ? min(240, videoHeight * 0.35) : min(48, videoHeight * 0.08)
        let availableBottom = captionHeight > 0 ? videoHeight - captionInset - captionHeight - 12 : videoHeight - 12
        let fits = orientationSettled && videoWidth > 40 && 12 + cardHeight <= availableBottom
        return ConversionNoticeBanner(session: session, presentation: noticePresentation, fits: fits,
            obscured: panel != nil || session.panelOpen || scrubbing || session.error != nil,
            openInfo: { panel = "info" })
            .frame(width: cardWidth).padding(.leading, 20).padding(.top, 12)
    }
    @ViewBuilder private func topCards(width: CGFloat, height: CGFloat, fillColumn: Bool = false, subtitleWidth: CGFloat, includeNotice: Bool = true) -> some View {
        let room = width - 360
        let beside = room >= 400
        let below = !beside || anchoredBelowNotice
        let cardWidth = max(1, below ? width - 40 : min(480, room))
        let subtitleReserve = PictureshowPlayerViewController.touchCaptionHeight(text: session.subtitleCue, preferences: session.preferences, width: max(1, subtitleWidth))
        let fits = orientationSettled && (voiceOver || !session.controlsVisible || (controlsHeight > 0 && height - controlsHeight - 36 > noticeHeight(width: cardWidth) + (subtitleReserve > 0 ? subtitleReserve + 24 : 0)))
        HStack(alignment: .top, spacing: 12) {
            if !below {
                if includeNotice {
                    ConversionNoticeBanner(session: session, presentation: noticePresentation, fits: fits, obscured: panel != nil || scrubbing || session.error != nil, openInfo: { panel = "info" })
                        .frame(width: cardWidth)
                }
                Spacer(minLength: 0)
            }
            VStack(alignment: .trailing, spacing: 12) {
                if includeNotice, below {
                    ConversionNoticeBanner(session: session, presentation: noticePresentation, fits: fits, obscured: panel != nil || scrubbing || session.error != nil, openInfo: { panel = "info" })
                    if anchoredBelowNotice, !(noticePresentation.active && fits && panel == nil && !scrubbing && session.error == nil) {
                        Color.clear.frame(height: reservedNoticeHeight).accessibilityHidden(true).allowsHitTesting(false)
                    }
                }
                if session.nextCardVisible, let next = session.nextItem {
                    ScrollView {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("Up next: \(next.name)").font(.headline).fixedSize(horizontal: false, vertical: true)
                        // Keep the action row at one origin when pause/panels suppress
                        // the number. The reserved line is never a reader cue.
                        ZStack(alignment: .leading) {
                            Text("Playing in 10 seconds").hidden().accessibilityHidden(true)
                            if let seconds = session.nextCountdown {
                                Text("Playing in \(seconds) seconds").accessibilityIdentifier("nextCountdown")
                            }
                        }.monospacedDigit()
                        ViewThatFits(in: .horizontal) {
                            HStack(spacing: 8) { nextActions; skipButton }
                            VStack(alignment: .leading, spacing: 10) {
                                HStack(spacing: 8) { nextActions }
                                skipButton
                            }
                        }.buttonStyle(PlayerCardActionStyle())
                    }.padding(16)
                    }.scrollBounceBehavior(.basedOnSize)
                        .frame(maxHeight: height > width ? nil : max(48, height - (session.controlsVisible ? controlsHeight : 0) - 36))
                        .fixedSize(horizontal: false, vertical: true).frame(maxWidth: fillColumn ? .infinity : nil, alignment: .leading).background(Theme.surface.opacity(0.96), in: RoundedRectangle(cornerRadius: 12))
                        .accessibilityElement(children: .contain)
                        .accessibilityIdentifier("nextEpisodeCard")
                        .background(GeometryReader { proxy in Color.clear.preference(key: CaptionObstacleFrames.self, value: ["next": proxy.frame(in: .global)]) })
                        .onAppear { if voiceOver { UIAccessibility.post(notification: .announcement, argument: "Up next: \(next.name). Play now or Cancel.") } }
                } else { skipButton }
            }.frame(maxWidth: below ? .infinity : 320, alignment: .trailing)
        }.buttonStyle(.bordered).tint(Theme.accent).foregroundStyle(.white)
            .onChange(of: "\(beside)|\(noticePresentation.active)|\(session.nextCardVisible)|\(session.skipSegment?.id ?? "")", initial: true) { _, _ in
                guard includeNotice else { return }
                let hasCard = session.nextCardVisible || session.skipSegment != nil
                if !hasCard { anchoredBelowNotice = false; reservedNoticeHeight = 0 }
                else if !beside, noticePresentation.active, fits, !anchoredBelowNotice {
                    anchoredBelowNotice = true; reservedNoticeHeight = noticeHeight(width: cardWidth)
                }
            }
    }
    private func noticeHeight(width: CGFloat) -> CGFloat {
        guard let notice = session.conversionNotice else { return 0 }
        let rect = CGSize(width: max(1, width - 72), height: .greatestFiniteMagnitude)
        let title = (notice.title as NSString).boundingRect(with: rect, options: .usesLineFragmentOrigin, attributes: [.font: UIFont.preferredFont(forTextStyle: .headline)], context: nil).height
        let body = ("\(notice.reason) \(notice.fix)" as NSString).boundingRect(with: rect, options: .usesLineFragmentOrigin, attributes: [.font: UIFont.preferredFont(forTextStyle: .callout)], context: nil).height
        return ceil(title + body + 38)
    }
    @ViewBuilder private var nextActions: some View {
        Button("Play now") { Task { await session.advance() } }.accessibilityIdentifier("playNext").fixedSize(horizontal: true, vertical: false)
            .buttonStyle(PlayerPrimaryCardActionStyle())
        Button("Cancel") { session.cancelContinuation(); showControls() }.accessibilityIdentifier("cancelNext").fixedSize(horizontal: true, vertical: false)
    }
    @ViewBuilder private var skipButton: some View {
        if let segment = session.skipSegment, session.preferences.segmentModes[segment.type] != .automatic {
            Button("Skip \(segment.type.rawValue.lowercased())") { session.skip(); interact() }
                .accessibilityIdentifier("skipSegment").fixedSize(horizontal: true, vertical: false).buttonStyle(PlayerCardActionStyle())
                .measureTouchCaptionObstacle("skip")
        }
    }
    private func controls(width: CGFloat, tablet: Bool, compact: Bool) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            if !compact { Text(session.item.name).font(.headline).fixedSize(horizontal: false, vertical: true).accessibilityIdentifier("playerTitle") }
            HStack {
                Text(time(session.positionTicks)).monospacedDigit().accessibilityIdentifier("playbackPosition")
                Spacer(); Text(session.status).font(.caption).accessibilityIdentifier("playbackMethod")
                if let duration = session.durationSeconds { Text(time(Ticks.from(seconds: duration))).monospacedDigit() }
            }.font(.caption)
            if let duration = session.durationSeconds, duration > 0 {
                Slider(value: Binding(get: { scrubbing ? preview : Ticks.seconds(session.positionTicks) }, set: { value in
                    preview = value
                    if !scrubbing { session.seek(toTicks: Ticks.from(seconds: value)); interact() }
                }), in: 0...duration, onEditingChanged: { editing in
                    scrubbing = editing; interact()
                    if editing { preview = Ticks.seconds(session.positionTicks); session.nativeSeekBegan(preview) }
                    else { session.seek(toTicks: Ticks.from(seconds: preview)) }
                }).accessibilityLabel("Current position").accessibilityIdentifier("playerScrubber")
            }
            if compact {
                HStack(spacing: 8) {
                    transportButtons
                    Menu { options(tablet: tablet) } label: {
                        HStack(alignment: .center, spacing: 6) {
                            Image(systemName: "ellipsis.circle").font(.system(size: 22, weight: .semibold)).accessibilityHidden(true)
                            Text("More").font(.subheadline.weight(.semibold))
                        }.padding(.horizontal, 13).minimumTouchTarget()
                    }.buttonStyle(PlayerTouchActionStyle())
                        .accessibilityLabel("More").accessibilityIdentifier("playerMore").accessibilityFocused($readerFocus, equals: "playerMore")
                    Spacer(minLength: 0)
                    endingButtons
                }
            } else if width >= 600 {
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 12) {
                        transportButtons
                        HStack(spacing: 8) { options(tablet: tablet) }.fixedSize()
                        Spacer(minLength: 0)
                        endingButtons
                    }
                    stackedControls(width: width, tablet: tablet)
                }
            } else { stackedControls(width: width, tablet: tablet) }
            if let orientationMessage { Text(orientationMessage).font(.caption) }
        }.padding(16).background(.black.opacity(0.85), in: RoundedRectangle(cornerRadius: 16)).foregroundStyle(.white)
    }
    private var transportButtons: some View {
        HStack(spacing: 8) {
            Button(session.isPaused ? "Play" : "Pause", systemImage: session.isPaused ? "play.fill" : "pause.fill") { session.togglePlayback(); interact() }.accessibilityIdentifier("playerPlayPause").font(.system(size: 28, weight: .semibold))
            Button("Back 10 seconds", systemImage: "gobackward.10") { session.seek(by: -10); interact() }
            Button("Forward 10 seconds", systemImage: "goforward.10") { session.seek(by: 10); interact() }
        }.font(.system(size: 22, weight: .semibold)).labelStyle(.iconOnly).buttonStyle(PlayerTouchActionStyle())
    }
    private var endingButtons: some View {
        HStack(spacing: 8) {
            NativeRoutePicker(enabled: !session.terminal && session.durationSeconds.map { Ticks.seconds(session.positionTicks) < $0 } != false, presentationChanged: { visible in
                session.panelOpen = visible; showControls()
            }).frame(width: 48, height: 48).accessibilityIdentifier("playerRoutePicker")
            Button("Stop", systemImage: "stop.fill", action: finish).accessibilityIdentifier("closePlayer")
        }.font(.system(size: 22, weight: .semibold)).labelStyle(.iconOnly).buttonStyle(PlayerTouchActionStyle())
    }
    private func stackedControls(width: CGFloat, tablet: Bool) -> some View {
        let widest = ["Audio and subtitles", "Playback info", "Speed: 1.75×", "Chapters"].map {
            ($0 as NSString).size(withAttributes: [.font: UIFont.preferredFont(forTextStyle: .subheadline)]).width + 32
        }.max() ?? 160
        let columns = max(1, min(width >= 600 ? 3 : 3, Int((width - 72) / widest)))
        return VStack(alignment: .leading, spacing: 12) {
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 8) { transportButtons; Spacer(minLength: 0); endingButtons }
                VStack(alignment: .leading, spacing: 12) {
                    transportButtons
                    HStack { Spacer(minLength: 0); endingButtons }
                }
            }
            if width >= 600 {
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 8) { options(tablet: tablet, expand: false) }
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: columns), spacing: 8) { options(tablet: tablet) }
                }
            } else {
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: columns), spacing: 8) { options(tablet: tablet) }
            }
        }
    }
    @ViewBuilder private func options(tablet: Bool, expand: Bool = true) -> some View {
        option("Audio and subtitles", id: "playerTracks", expand: expand) { tracks() }
        option("Seek", id: "playerSeek", expand: expand) { panel = "seek" }
        option("Chapters", id: "playerChapters", expand: expand) { panel = "chapters" }
        option("Speed: \(PlaybackSpeeds.label(session.playbackRate))", id: "playerSpeed", expand: expand) { panel = "speed" }
        option("Playback info", id: "playerInfo", expand: expand) { panel = "info" }
        if !tablet { option("Rotate", id: "rotatePlayer", expand: expand) { rotate() } }
    }
    private func option(_ label: String, id: String, expand: Bool = true, action: @escaping () -> Void) -> some View {
        Button { opener = session.nextCardVisible && scene?.interfaceOrientation.isLandscape == true ? "playerMore" : id; action(); interact() } label: { Text(label).font(.subheadline).fixedSize().frame(maxWidth: expand ? .infinity : nil).minimumTouchTarget() }
            .buttonStyle(PlayerOptionStyle()).accessibilityIdentifier(id).accessibilityFocused($readerFocus, equals: id)
    }
    private func interact() { interaction += 1 }
    private func showControls() { interact(); session.controlsVisible = true }
    private func time(_ ticks: Int64) -> String { Catalog.resumeLabel(ticks).replacingOccurrences(of: "Resume from ", with: "") }
    private var scene: UIWindowScene? { UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first { $0.activationState == .foregroundActive } }
    private func updateOrientation(tablet: Bool) {
        guard !tablet, let scene else { if tablet { restoreOrientation() }; return }
        guard originalOrientation == nil else { return }
        originalOrientation = scene.interfaceOrientation.isLandscape ? .landscape : .portrait
        requestOrientation(.landscapeRight)
    }
    private func rotate() { guard let scene else { return }; requestOrientation(scene.interfaceOrientation.isLandscape ? .portrait : .landscapeRight) }
    private func restoreOrientation() { if let originalOrientation { requestOrientation(originalOrientation); self.originalOrientation = nil } }
    private func requestOrientation(_ mask: UIInterfaceOrientationMask) {
        orientationMessage = nil
        scene?.requestGeometryUpdate(.iOS(interfaceOrientations: mask)) { _ in
            Task { @MainActor in orientationMessage = "Use device rotation or a full-screen window to change orientation."; orientationSettled = true }
        }
    }
}
/// The public system picker supplies native AirPlay destinations and active-route
/// indication. Jellyfin session control remains the separate catalog chooser.
private struct NativeRoutePicker: UIViewRepresentable {
    let enabled: Bool
    let presentationChanged: (Bool) -> Void
    func makeUIView(context: Context) -> AVRoutePickerView {
        let view = AVRoutePickerView()
        view.prioritizesVideoDevices = true; view.tintColor = .white; view.activeTintColor = UIColor(Theme.accent)
        view.delegate = context.coordinator
        return view
    }
    func updateUIView(_ view: AVRoutePickerView, context: Context) {
        context.coordinator.changed = presentationChanged
        view.isUserInteractionEnabled = enabled; view.alpha = enabled ? 1 : 0.4
        for button in view.subviews.compactMap({ $0 as? UIButton }) {
            button.isEnabled = enabled
            button.accessibilityLabel = "Play on"; button.accessibilityHint = "Choose an AirPlay output"
        }
    }
    func makeCoordinator() -> Coordinator { Coordinator(changed: presentationChanged) }
    final class Coordinator: NSObject, AVRoutePickerViewDelegate {
        var changed: (Bool) -> Void
        init(changed: @escaping (Bool) -> Void) { self.changed = changed }
        func routePickerViewWillBeginPresentingRoutes(_ routePickerView: AVRoutePickerView) { changed(true) }
        func routePickerViewDidEndPresentingRoutes(_ routePickerView: AVRoutePickerView) { changed(false) }
    }
}
private struct PlayerControlsHeight: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) }
}
private struct PlayerPrimaryCardActionStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.padding(.horizontal, 12).frame(minHeight: 48)
            .foregroundStyle(Theme.background)
            .background(Theme.accent.opacity(configuration.isPressed ? 0.8 : 1), in: RoundedRectangle(cornerRadius: 10))
            .contentShape(Rectangle())
    }
}
private struct PlayerCardActionStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.padding(.horizontal, 12).frame(minHeight: 48)
            .background(Theme.accent.opacity(configuration.isPressed ? 0.4 : 0.2), in: RoundedRectangle(cornerRadius: 10))
            .contentShape(Rectangle())
    }
}
private struct PlayerOptionStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.padding(.horizontal, 8)
            .background(Theme.accent.opacity(configuration.isPressed ? 0.4 : 0.2), in: RoundedRectangle(cornerRadius: 10))
            .contentShape(Rectangle())
    }
}
private struct PlayerTouchActionStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.frame(minWidth: 48, minHeight: 48).contentShape(Rectangle())
            .background(configuration.isPressed ? Color.white.opacity(0.2) : Color.clear, in: RoundedRectangle(cornerRadius: 8))
    }
}
#endif

#if os(iOS)
private struct CaptionObstacleFrames: PreferenceKey {
    static let defaultValue: [String: CGRect] = [:]
    static func reduce(value: inout [String: CGRect], nextValue: () -> [String: CGRect]) {
        value.merge(nextValue(), uniquingKeysWith: { _, latest in latest })
    }
}
#endif

private extension View {
    @ViewBuilder func measureTouchCaptionObstacle(_ id: String) -> some View {
        #if os(iOS)
        background(GeometryReader { proxy in Color.clear.preference(key: CaptionObstacleFrames.self, value: [id: proxy.frame(in: .global)]) })
        #else
        self
        #endif
    }
}
