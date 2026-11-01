// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import SwiftUI
import AVKit
import PictureshowCore
import OSLog

struct PlayerScreen: View {
    @ObservedObject var session: PlaybackSession
    var close: () async -> Void
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @State private var panel: String?
    @State private var closing = false
    @State private var orientationError: String?
    var body: some View {
        ZStack(alignment: .top) {
            #if os(iOS)
            // Keep app actions outside AVKit's viewport: iOS exposes no transport-menu
            // extension or visibility callback. An overlay collides with native controls.
            VStack(spacing: 0) {
                NativePlayer(session: session, close: finish)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                if let orientationError { Text(orientationError).font(.caption).padding(8) }
                if let segment = session.skipSegment, session.preferences.segmentModes[segment.type] != .automatic {
                    Button("Skip \(segment.type.rawValue)") { session.skip() }.buttonStyle(ProminentActionStyle()).padding(8)
                }
                if let next = session.nextItem, session.nextCountdown != nil {
                    HStack {
                        Button("Play next: \(next.name)") { Task { await session.advance() } }
                        Button("Cancel next episode") { session.cancelContinuation() }
                    }.buttonStyle(ProminentActionStyle()).padding(8)
                }
                Text(session.item.name).font(.subheadline.weight(.medium)).lineLimit(2)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16).padding(.top, 8).padding(.bottom, 4)
                    .background(Theme.surface).accessibilityAddTraits(.isHeader).accessibilityIdentifier("playerTitle")
                HStack(spacing: 0) {
                    Button("Back to details", systemImage: "chevron.left", action: finish).accessibilityIdentifier("closePlayer")
                    Button("Audio and subtitles", systemImage: "captions.bubble") { panel = "tracks" }.accessibilityIdentifier("playerTracks")
                    Button("Seek", systemImage: "slider.horizontal.3") { panel = "seek" }.accessibilityIdentifier("playerSeek")
                    Button("Chapters", systemImage: "list.bullet") { panel = "chapters" }.accessibilityIdentifier("playerChapters")
                    Button("Rotate portrait or landscape", systemImage: "rotate.right") { rotate() }.accessibilityIdentifier("rotatePlayer")
                    Spacer(minLength: 4)
                    VStack(alignment: .trailing, spacing: 2) {
                        Text(session.status).accessibilityIdentifier("playbackMethod")
                        Text(clock(session.positionTicks)).monospacedDigit().accessibilityIdentifier("playbackPosition")
                    }.font(.caption).fixedSize(horizontal: false, vertical: true)
                }
                .buttonStyle(PlayerActionStyle()).labelStyle(.iconOnly)
                .padding(.horizontal, 8).padding(.vertical, 4).background(Theme.surface)
            }
            #else
            NativePlayer(session: session, close: finish).ignoresSafeArea()
            #endif
            if session.waitingForVideo || session.error != nil || session.reportError != nil {
                VStack(spacing: 20) {
                    if session.waitingForVideo {
                        ProgressView(session.status).accessibilityIdentifier("playbackWaiting")
                    }
                    if let error = session.error { Text(error).foregroundStyle(.orange) }
                    if let error = session.reportError { Text(error) }
                    Button("Back to details", action: finish).accessibilityIdentifier("loadingBack")
                }.padding(32).background(.black.opacity(0.85), in: RoundedRectangle(cornerRadius: 16)).padding(.top, 100)
            }
        }
        .background(.black)
        .announceStatus(session.subtitleStatus)
        .onDisappear { session.beginClosing() }
        .onChange(of: session.terminal) { _, value in if value, session.error == nil { finish() } }
        .onChange(of: scenePhase) { _, value in if value != .active { session.backgrounded() } }
        .onChange(of: panel) { _, value in session.panelOpen = value != nil }
        .sheet(isPresented: Binding(get: { panel != nil }, set: { if !$0 { panel = nil } })) {
            PlayerOptions(session: session, kind: panel ?? "tracks") { panel = nil }
        }
    }

    private func rotate() {
        #if os(iOS)
        guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first(where: { $0.activationState == .foregroundActive }) else { return }
        let target: UIInterfaceOrientationMask = scene.interfaceOrientation.isLandscape ? .portrait : .landscapeRight
        scene.requestGeometryUpdate(.iOS(interfaceOrientations: target)) { error in
            Task { @MainActor in orientationError = "This window could not rotate. Use the device orientation or a full-screen window." }
        }
        #endif
    }
    private func finish() { guard !closing else { return }; closing = true; session.beginClosing(); Task { await close(); dismiss() } }
}


#if os(iOS)
private struct PlayerActionStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.frame(width: 48, height: 48)
            .background(configuration.isPressed ? Color.white.opacity(0.2) : .clear, in: RoundedRectangle(cornerRadius: 8))
            .contentShape(Rectangle())
    }
}
#endif

private func trackName(_ stream: MediaStream, indexFirst: Bool = false) -> String {
    var parts = [stream.label]
    let lower = stream.label.lowercased()
    let labelTokens = lower.split { !$0.isLetter && !$0.isNumber && $0 != "-" }
    // Short language codes must be whole tokens: "en" inside "Commentary"
    // does not tell a viewer that the track is English.
    if let language = stream.language, let name = Locale.current.localizedString(forLanguageCode: language), !lower.contains(name.lowercased()), !labelTokens.contains(Substring(language.lowercased())) { parts.append(name) }
    if let codec = stream.codec, !lower.contains(codec.lowercased()) { parts.append(codec.uppercased()) }
    if let channels = stream.channels, !lower.contains("\(channels) channel") { parts.append("\(channels) channels") }
    if stream.isForced && (!lower.contains("forced") || lower.contains("non-forced") || lower.contains("non forced")) { parts.append("Forced") }
    if stream.isHearingImpaired && !lower.contains("hearing impaired") && !lower.contains("sdh") { parts.append("Hearing impaired") }
    // AVKit's TV popover truncates long track titles. Keep the distinguishing
    // stream number visible there while preserving the full spoken metadata.
    if indexFirst { parts.insert("Stream \(stream.index)", at: 0) }
    else { parts.append("Stream \(stream.index)") }
    return parts.joined(separator: " · ")
}
private func clock(_ ticks: Int64) -> String { Catalog.resumeLabel(ticks).replacingOccurrences(of: "Resume from ", with: "") }

struct PlayerOptions: View {
    @ObservedObject var session: PlaybackSession
    @Environment(\.dismiss) private var dismiss
    let kind: String
    let done: () -> Void
    private var audio: Int? { session.decision?.audioIndex }
    private var subtitle: Int? { session.decision?.subtitleIndex }
    var body: some View {
        NavigationStack {
            List {
                if kind == "seek" {
                    SeekOptions(session: session)
                } else if kind == "chapters" {
                    if session.chapters.isEmpty { Text("No chapters are available.") }
                    ForEach(Array(session.chapters.enumerated()), id: \.offset) { index, chapter in
                        Button { session.seek(toTicks: chapter.startTicks); closePanel() } label: {
                            HStack { ChapterArtwork(session: session, index: index); Text("\(chapter.name.isEmpty ? "Chapter \(index + 1)" : chapter.name) · \(clock(chapter.startTicks))") }.frame(maxWidth: .infinity, alignment: .leading).minimumTouchTarget()
                        }
                            .accessibilityValue(currentChapter(index) ? "Current chapter" : "")
                    }
                } else {
                    if session.changingTracks { ProgressView("Applying tracks…").accessibilityIdentifier("applyingTracks") }
                    Section("Audio") {
                        track("Off", index: -1, type: "Audio", selected: audio == -1)
                        ForEach(streams("Audio")) { track(trackName($0), index: $0.index, type: "Audio", selected: audio == $0.index) }
                    }
                    Section("Subtitles") {
                        track("Off", index: -1, type: "Subtitle", selected: subtitle == -1)
                        ForEach(streams("Subtitle")) { track(trackName($0), index: $0.index, type: "Subtitle", selected: subtitle == $0.index) }
                        Text(session.subtitleTimingExplanation).font(.caption)
                        if let status = session.subtitleStatus { Text(status).font(.caption).accessibilityIdentifier("subtitleLoadingStatus") }
                    }
                    if let error = session.extrasError { Text(error) }
                }
            }
            .pictureshowNavigationTitle(kind == "seek" ? "Seek" : kind == "chapters" ? "Chapters" : "Audio and subtitles")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done", action: closePanel).accessibilityIdentifier("playerOptionsDone") } }
        }
    }
    private func closePanel() { done(); dismiss() }
    private func streams(_ type: String) -> [MediaStream] { session.decision?.source.mediaStreams.filter { $0.type == type } ?? [] }
    private func track(_ title: String, index: Int, type: String, selected: Bool) -> some View {
        Button {
            #if DEBUG
            Logger(subsystem: "app.pictureshow", category: "Playback").notice("Track action type=\(type, privacy: .public) index=\(index)")
            #endif
            let chosenAudio = type == "Audio" ? index : session.decision?.audioIndex
            let chosenSubtitle = type == "Subtitle" ? index : session.decision?.subtitleIndex
            closePanel()
            Task { await session.changeTracks(audio: chosenAudio, subtitle: chosenSubtitle) }
        } label: {
            HStack { Text(title); Spacer(); if selected { Image(systemName: "checkmark").accessibilityHidden(true) } }.minimumTouchTarget()
        }.disabled(session.changingTracks)
            .accessibilityValue(session.changingTracks ? "Applying tracks" : selected ? "Selected" : "").accessibilityIdentifier("\(type.lowercased())-\(index)")
    }
    private func currentChapter(_ index: Int) -> Bool {
        session.chapters[index].startTicks <= session.positionTicks && (index + 1 == session.chapters.count || session.chapters[index + 1].startTicks > session.positionTicks)
    }
}

struct NativePlayer: UIViewControllerRepresentable {
    @ObservedObject var session: PlaybackSession
    var close: () -> Void
    func makeUIViewController(context: Context) -> PictureshowPlayerViewController {
        let controller = PictureshowPlayerViewController()
        controller.player = session.player; controller.delegate = context.coordinator
        controller.showsPlaybackControls = true; controller.videoGravity = .resizeAspect
        controller.allowsPictureInPicturePlayback = false
        // Identify the native video frame without changing accessibility grouping.
        // Touch tests must reveal controls inside this frame, not the app strip.
        controller.view.accessibilityIdentifier = "pictureshowNativePlayer"
        #if os(tvOS)
        controller.playbackControlsIncludeInfoViews = true; controller.transportBarIncludesTitleView = true
        let info = UIHostingController(rootView: PlaybackInformation(session: session)); info.title = "Pictureshow"
        info.preferredContentSize = CGSize(width: 1200, height: 250)
        controller.customInfoViewControllers = [info]
        #endif
        updateUIViewController(controller, context: context)
        return controller
    }
    func updateUIViewController(_ controller: PictureshowPlayerViewController, context: Context) {
        context.coordinator.close = close
        controller.showSubtitle(session.subtitleCue, controlsVisible: session.controlsVisible, preferences: session.preferences)
        #if os(tvOS)
        let continuation = session.nextCountdown != nil ? session.nextItem : nil
        context.coordinator.updateContinuation(continuation, countdown: session.nextCountdown)
        let signature = "\(continuation?.id ?? "")|\(session.item.id)|\(session.decision?.playSessionId ?? "")|\(session.decision?.audioIndex ?? -999)|\(session.decision?.subtitleIndex ?? -999)|\(session.chapters.count)|\(session.changingTracks)"
        if context.coordinator.menuSignature != signature {
            context.coordinator.menuSignature = signature
            func tracks(_ type: String) -> UIMenu {
                let values = session.decision?.source.mediaStreams.filter { $0.type == type } ?? []
                let selected = type == "Audio" ? session.decision?.audioIndex : session.decision?.subtitleIndex
                let off = UIAction(title: "Off", identifier: UIAction.Identifier("\(type.lowercased())--1"), attributes: session.changingTracks ? [.disabled] : [], state: selected == -1 ? .on : .off) { _ in
                    Task { await session.changeTracks(audio: type == "Audio" ? -1 : session.decision?.audioIndex, subtitle: type == "Subtitle" ? -1 : session.decision?.subtitleIndex) }
                }
                let actions = values.map { stream in
                    UIAction(title: trackName(stream, indexFirst: true), subtitle: nil, identifier: UIAction.Identifier("\(type.lowercased())-\(stream.index)"), attributes: session.changingTracks ? [.disabled] : [], state: selected == stream.index ? .on : .off) { _ in
                        Task { await session.changeTracks(audio: type == "Audio" ? stream.index : session.decision?.audioIndex, subtitle: type == "Subtitle" ? stream.index : session.decision?.subtitleIndex) }
                    }
                }
                return UIMenu(title: type == "Audio" ? "Audio" : "Subtitles", image: UIImage(systemName: type == "Audio" ? "speaker.wave.2" : "captions.bubble"), children: [off] + actions)
            }
            let chapters = UIMenu(title: "Chapters", image: UIImage(systemName: "list.bullet"), children: session.chapters.enumerated().map { index, chapter in
                UIAction(title: chapter.name.isEmpty ? "Chapter \(index + 1)" : chapter.name, subtitle: clock(chapter.startTicks)) { _ in session.seek(toTicks: chapter.startTicks) }
            })
            let stop = UIAction(title: "Back to details", image: UIImage(systemName: "stop.fill"), identifier: UIAction.Identifier("closePlayer")) { _ in close() }
            // Contextual actions are hidden while AVKit's transport is visible. Keep
            // the same continuation reachable in the native bar during that state.
            let continuationMenus: [UIMenuElement] = context.coordinator.continuationTransportMenu.map { [$0] } ?? []
            controller.transportBarCustomMenuItems = continuationMenus
                + [tracks("Audio"), tracks("Subtitle")] + (session.chapters.isEmpty ? [] : [chapters]) + [stop]
        }
        var contextActions: [UIAction] = []
        if let segment = session.skipSegment, session.preferences.segmentModes[segment.type] != .automatic {
            contextActions.append(UIAction(title: "Skip \(segment.type.rawValue)", identifier: .init("skipSegment")) { _ in session.skip() })
        }
        contextActions += context.coordinator.continuationContextActions
        let actionSignature = (session.skipSegment?.id ?? "") + "|" + contextActions.map { $0.identifier.rawValue }.joined(separator: "|")
        if context.coordinator.actionSignature != actionSignature {
            context.coordinator.actionSignature = actionSignature
            controller.contextualActions = contextActions
        }
        #endif
    }
    func makeCoordinator() -> Coordinator { Coordinator(session: session, close: close) }
    final class Coordinator: NSObject, AVPlayerViewControllerDelegate {
        let session: PlaybackSession
        var close: () -> Void
        var menuSignature = "", actionSignature = ""
        init(session: PlaybackSession, close: @escaping () -> Void) { self.session = session; self.close = close }
        #if os(tvOS)
        private var continuationID: String?
        var continuationTransportActions: [UIAction] = []
        #if os(tvOS)
        var continuationTransportMenu: UIMenu?
        #endif
        var continuationContextActions: [UIAction] = []
        func updateContinuation(_ next: MediaItem?, countdown: Int?) {
            guard let next, let countdown else {
                continuationID = nil; continuationTransportActions = []; continuationContextActions = []
                #if os(tvOS)
                continuationTransportMenu = nil
                #endif
                return
            }
            if continuationID != next.id {
                continuationID = next.id
                func play(_ identifier: String) -> UIAction {
                    UIAction(title: "Play next: \(next.name)", image: UIImage(systemName: "forward.end.fill"), identifier: .init(identifier)) { [weak self] _ in
                        guard let self, self.session.nextItem?.id == next.id, self.session.nextCountdown != nil else { return }
                        Logger(subsystem: "app.pictureshow", category: "Playback").notice("Manual next activated item=\(next.id, privacy: .public) ticks=\(self.session.positionTicks)")
                        Task { await self.session.advance() }
                    }
                }
                func cancel(_ identifier: String) -> UIAction {
                    UIAction(title: "Cancel next episode", identifier: .init(identifier)) { [weak self] _ in self?.session.cancelContinuation() }
                }
                continuationTransportActions = [play("playNextTransport"), cancel("cancelNextTransport")]
                #if os(tvOS)
                // AVKit renders transport actions as glyphs. A native menu keeps
                // the next title, countdown and Cancel readable when opened.
                continuationTransportMenu = UIMenu(title: "Next episode", image: UIImage(systemName: "forward.end.fill"),
                                                   identifier: .init("nextEpisodeMenu"), children: continuationTransportActions)
                #endif
                continuationContextActions = [play("playNext"), cancel("cancelNext")]
                actionSignature = "" // A different episode may have the same action identifiers.
            }
            // Mutate the retained action rather than replacing AVKit's focused controls
            // every second. A countdown tick must not restart contextual presentation.
            continuationContextActions.first?.title = "Play next: \(next.name) (\(countdown))"
            #if os(tvOS)
            continuationTransportActions.first?.title = "Play next: \(next.name) (\(countdown))"
            #endif
        }
        func playerViewControllerShouldDismiss(_ controller: AVPlayerViewController) -> Bool { close(); return false }
        func playerViewController(_ controller: AVPlayerViewController, willTransitionToVisibilityOfTransportBar visible: Bool, with coordinator: AVPlayerViewControllerAnimationCoordinator) { Task { @MainActor in session.controlsVisible = visible } }
        func playerViewController(_ controller: AVPlayerViewController, timeToSeekAfterUserNavigatedFrom oldTime: CMTime, to targetTime: CMTime) -> CMTime { Task { @MainActor in session.nativeSeekBegan(targetTime.seconds) }; return targetTime }
        #endif
    }
}

/// Optional plain-text captions live in AVKit's video overlay, never in its asset.
/// Loading failure cannot hold the transport or change its media clock.
final class PictureshowPlayerViewController: AVPlayerViewController {
    private let caption = UILabel()
    private var controlsVisible = false
    override func viewDidLoad() {
        super.viewDidLoad()
        caption.numberOfLines = 0; caption.textAlignment = .center
        caption.textColor = .white; caption.isUserInteractionEnabled = false
        caption.isAccessibilityElement = false // Clock/cue ticks are not announcements.
        contentOverlayView?.addSubview(caption)
    }
    func showSubtitle(_ text: String?, controlsVisible: Bool, preferences: PlaybackPreferences) {
        loadViewIfNeeded()
        caption.text = text; caption.isHidden = text == nil
        self.controlsVisible = controlsVisible
        #if os(tvOS)
        let base: CGFloat = 34
        #else
        let base: CGFloat = 22
        #endif
        caption.font = UIFontMetrics(forTextStyle: .body).scaledFont(for: .systemFont(ofSize: base * CGFloat(preferences.subtitleSize.scale ?? 1)))
        caption.backgroundColor = preferences.subtitleBackground == .box ? UIColor.black.withAlphaComponent(0.8) : .clear
        caption.layer.shadowColor = UIColor.black.cgColor
        caption.layer.shadowOpacity = preferences.subtitleBackground == .none ? 0 : 1
        caption.layer.shadowRadius = 2; caption.layer.shadowOffset = CGSize(width: 1, height: 1)
        view.setNeedsLayout()
    }
    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        guard let overlay = contentOverlayView else { return }
        #if os(tvOS)
        // videoBounds is iOS-only. Reproduce AVKit's resizeAspect geometry from
        // the item's presentation size; unknown video never publishes a cue box.
        let size = player?.currentItem?.presentationSize ?? .zero
        guard size.width > 0, size.height > 0 else { caption.frame = .zero; return }
        let width = min(overlay.bounds.width, overlay.bounds.height * size.width / size.height)
        let height = width * size.height / size.width
        let video = CGRect(x: overlay.bounds.midX - width / 2, y: overlay.bounds.midY - height / 2, width: width, height: height)
        #else
        let video = overlay.convert(videoBounds, from: view).intersection(overlay.bounds)
        #endif
        guard !video.isNull, video.width > 0, video.height > 0 else { caption.frame = .zero; return }
        let captionWidth = video.width * 0.9
        let captionHeight = min(video.height * 0.6, caption.sizeThatFits(CGSize(width: captionWidth, height: .greatestFiniteMagnitude)).height + 8)
        let inset = controlsVisible ? min(240, video.height * 0.35) : min(48, video.height * 0.08)
        caption.frame = CGRect(x: video.midX - captionWidth / 2, y: max(video.minY, video.maxY - inset - captionHeight), width: captionWidth, height: captionHeight)
    }
}

private struct PlaybackInformation: View {
    @ObservedObject var session: PlaybackSession
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text(session.item.name).font(.title2).accessibilityAddTraits(.isHeader)
                HStack { Text(session.status).accessibilityIdentifier("playbackMethod"); Text(clock(session.positionTicks)).accessibilityIdentifier("playbackPosition") }
                Text(session.subtitleTimingExplanation).font(.caption)
                if let status = session.subtitleStatus { Text(status).font(.caption).accessibilityIdentifier("subtitleLoadingStatus") }
                SeekOptions(session: session)
                if let warning = session.reportError ?? session.extrasError { Text(warning).foregroundStyle(.orange) }
            }.frame(maxWidth: .infinity, alignment: .leading).padding(24)
        }
    }
}

private struct ChapterArtwork: View {
    let session: PlaybackSession
    let index: Int
    @State private var image: UIImage?
    var body: some View {
        Group { if let image { Image(uiImage: image).resizable().scaledToFit().frame(width: 120, height: 68) } }
            .accessibilityHidden(true).task { image = await session.chapterImage(index: index) }
    }
}
private struct SeekOptions: View {
    @ObservedObject var session: PlaybackSession
    @State private var target: Double = 0
    @State private var preview: UIImage?
    @State private var hidePreview = false
    @State private var editing = false
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Seek to \(clock(Ticks.from(seconds: target)))").monospacedDigit()
            if let preview, !hidePreview { Image(uiImage: preview).resizable().scaledToFit().frame(maxWidth: 320, maxHeight: 180).accessibilityHidden(true) }
            #if os(iOS)
            if let duration = session.durationSeconds {
                Slider(value: Binding(get: { target }, set: { value in
                    target = value
                    // Accessibility adjustments need not send drag begin/end events.
                    // Commit those changes here; physical drags commit once on release.
                    if !editing { session.seek(toTicks: Ticks.from(seconds: value)) }
                }), in: 0...duration, onEditingChanged: { value in
                    let wasEditing = editing; editing = value
                    if value { session.nativeSeekBegan(target) }
                    else if wasEditing { session.seek(toTicks: Ticks.from(seconds: target)) }
                }).accessibilityLabel("Seek position").accessibilityValue(clock(Ticks.from(seconds: target)))
            }
            #endif
            HStack {
                Button { adjust(-10) } label: { Text("Back 10 seconds").minimumTouchTarget() }.accessibilityIdentifier("seekBackward")
                Button { adjust(10) } label: { Text("Forward 10 seconds").minimumTouchTarget() }.accessibilityIdentifier("seekForward")
            }
            #if os(iOS)
            // This HStack shares one List row. Independent button styles prevent the
            // automatic row action from activating both opposing seek controls.
            .buttonStyle(.borderless)
            #else
            .secondaryActionStyle()
            #endif
        }
        .onAppear { target = Ticks.seconds(session.positionTicks) }
        .task(id: target) {
            hidePreview = false
            preview = await session.seekPreview(ticks: Ticks.from(seconds: target))
            do { try await Task.sleep(for: .seconds(2)) } catch { return }
            hidePreview = true
        }
    }
    private func adjust(_ delta: Double) {
        // The panel may have stayed open while video advanced. Relative buttons
        // follow the current/pending player position, not the old preview target.
        target = Ticks.seconds(session.seek(by: delta))
    }
}
