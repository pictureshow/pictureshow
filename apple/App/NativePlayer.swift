// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import SwiftUI
import AVKit
import PictureshowCore
import OSLog
import MediaAccessibility
import Combine

struct PlayerScreen: View {
    @ObservedObject var session: PlaybackSession
    var close: () async -> Void
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @State private var panel: String?
    @State private var closing = false
    @State private var orientationError: String?
    @ObservedObject private var trackMenus: TrackLabelCache
    init(session: PlaybackSession, close: @escaping () async -> Void) {
        self.session = session; self.close = close
        self.trackMenus = session.trackLabels
    }
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
                    Button("Audio and subtitles", systemImage: "captions.bubble") {
                        guard session.error == nil, !session.terminal else { return }
                        trackMenus.requestOpen()
                    }.accessibilityIdentifier("playerTracks")
                        .accessibilityValue(trackMenus.waitingToOpen ? "Preparing audio and subtitles" : "")
                    Button("Seek", systemImage: "slider.horizontal.3") { trackMenus.cancelOpen(); panel = "seek" }.accessibilityIdentifier("playerSeek")
                    Button("Chapters", systemImage: "list.bullet") { trackMenus.cancelOpen(); panel = "chapters" }.accessibilityIdentifier("playerChapters")
                    Button("Rotate portrait or landscape", systemImage: "rotate.right") { rotate() }.accessibilityIdentifier("rotatePlayer")
                    #if DEBUG
                    if ProcessInfo.processInfo.environment["PICTURESHOW_TEST_TRACK_MENU_GATE"] != nil {
                        Button("Replace fixture source", systemImage: "arrow.triangle.2.circlepath") { Task { await session.selectTrack(type: "Audio", index: session.decision?.audioIndex == 13 ? 14 : 13) } }
                            .accessibilityIdentifier("fixtureReplaceTrackSource")
                    }
                    #endif
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
            NativePlayer(session: session, close: finish, openPendingTracks: {
                trackMenus.requestOpen()
                panel = "tracks"
            }).ignoresSafeArea()
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
        .onChange(of: trackMenus.openMenu) { _, value in
            #if os(iOS)
            if value != nil { panel = "tracks" }
            else if panel == "tracks" { panel = nil }
            #endif
        }
        .onChange(of: panel) { old, value in
            session.panelOpen = value != nil
            if old == "tracks", value == nil { trackMenus.cancelOpen() }
        }
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

nonisolated private func trackDescriptionParts(_ stream: MediaStream) -> [String] {
    var parts = [stream.label]
    let lower = stream.label.lowercased()
    let labelTokens = lower.split { !$0.isLetter && !$0.isNumber && $0 != "-" }
    // Short language codes must be whole tokens: "en" inside "Commentary"
    // does not tell a viewer that the track is English.
    if let language = stream.language, let name = Locale.current.localizedString(forLanguageCode: language), !lower.contains(name.lowercased()), !labelTokens.contains(Substring(language.lowercased())) { parts.append(name) }
    if let title = stream.title, !title.isEmpty, !lower.contains(title.lowercased()) { parts.append(title) }
    if let codec = stream.codec, !lower.contains(codec.lowercased()) { parts.append(codec.uppercased()) }
    if let channels = stream.channels, !lower.contains("\(channels) channel") { parts.append("\(channels) channels") }
    if stream.isForced && (!lower.contains("forced") || lower.contains("non-forced") || lower.contains("non forced")) { parts.append("Forced") }
    if stream.isHearingImpaired && !lower.contains("hearing impaired") && !lower.contains("sdh") { parts.append("Hearing impaired") }
    if stream.isDefault && !lower.contains("default") { parts.append("Default") }
    return parts
}

private func clock(_ ticks: Int64) -> String { Catalog.resumeLabel(ticks).replacingOccurrences(of: "Resume from ", with: "") }

struct TrackLabel: Identifiable, Sendable, Equatable {
    let id: Int
    let title: String
    let menuTitle: String
}
struct PreparedTrackMenu: Sendable, Equatable {
    let generation: Int
    let audio: [TrackLabel]
    let subtitles: [TrackLabel]
    func values(_ type: String) -> [TrackLabel] { type == "Audio" ? audio : subtitles }
}
struct TrackMenuPresentation: Identifiable, Equatable {
    let id: Int
    let menu: PreparedTrackMenu
}

/// Metadata work starts at source arrival, independently of clock, selection and opens.
/// Only a complete pair of menus is published; obsolete workers have no publication rights.
@MainActor final class TrackLabelCache: ObservableObject {
    typealias Builder = @Sendable ([MediaStream], Int) -> PreparedTrackMenu
    @Published private(set) var prepared: PreparedTrackMenu?
    @Published private(set) var openMenu: TrackMenuPresentation?
    @Published private(set) var waitingToOpen = false
    @Published private(set) var dismissalRevision = 0
    private var streams: [MediaStream] = []
    private var sourceID: String?
    private(set) var worker: Task<Void, Never>?
    private let builder: Builder
    private var requestID = 0
    private var pending: (id: Int, followsFirstSource: Bool, completion: (TrackMenuPresentation?) -> Void)?
    private var hasMetadata = false
    private(set) var labelsBuilt = 0
    private(set) var cacheRevision = 0
    var audio: [TrackLabel] { prepared?.audio ?? [] }
    var subtitles: [TrackLabel] { prepared?.subtitles ?? [] }

    init(builder: @escaping Builder = { TrackLabelCache.buildForPlayback($0, generation: $1) }) { self.builder = builder }

    nonisolated private static func buildForPlayback(_ streams: [MediaStream], generation: Int) -> PreparedTrackMenu {
        #if DEBUG
        // Opt-in loopback fixture holds the actual detached worker. It supplies no
        // menu data or verdict; releasing it resumes the production label builder.
        if let address = ProcessInfo.processInfo.environment["PICTURESHOW_TEST_TRACK_MENU_GATE"],
           let base = URL(string: address), base.host == "127.0.0.1", base.scheme == "http" {
            let thread = Thread.isMainThread ? "main" : "background"
            let url = base.appendingPathComponent("fixture-menu-worker/\(generation)/\(thread)")
            let ready = DispatchSemaphore(value: 0)
            let task = URLSession.shared.dataTask(with: url) { _, _, _ in ready.signal() }
            task.resume()
            if ready.wait(timeout: .now() + 40) != .success { task.cancel() }
        }
        #endif
        return build(streams, generation: generation)
    }

    nonisolated static func build(_ streams: [MediaStream], generation: Int) -> PreparedTrackMenu {
        func labels(_ type: String) -> [TrackLabel] {
            streams.filter { $0.type == type }.map {
                let parts = trackDescriptionParts($0), index = "Stream \($0.index)"
                return TrackLabel(id: $0.index, title: (parts + [index]).joined(separator: " · "),
                                  menuTitle: ([index] + parts).joined(separator: " · "))
            }
        }
        return PreparedTrackMenu(generation: generation, audio: labels("Audio"), subtitles: labels("Subtitle"))
    }

    // The one-argument form preserves the current source for explicit metadata edits.
    func update(_ values: [MediaStream]) { update(values, sourceID: sourceID ?? "metadata") }
    func update(_ values: [MediaStream], sourceID: String?) {
        guard !hasMetadata || values != streams || self.sourceID != sourceID else { return }
        let followsFirstSource = self.sourceID == nil && sourceID != nil && pending?.followsFirstSource == true
        if !followsFirstSource { cancelOpen() }
        worker?.cancel()
        streams = values; self.sourceID = sourceID; hasMetadata = true
        cacheRevision += 1; prepared = nil
        guard sourceID != nil else { return }
        let generation = cacheRevision, builder = builder
        worker = Task { [weak self] in
            let menu = await Task.detached(priority: .userInitiated) { builder(values, generation) }.value
            guard let self, !Task.isCancelled, self.cacheRevision == generation else { return }
            self.labelsBuilt += menu.audio.count + menu.subtitles.count
            #if DEBUG
            Logger(subsystem: "app.pictureshow", category: "Playback").notice("track-menu-prepared generation=\(generation) pending=\(self.pending != nil)")
            #endif
            self.prepared = menu
            if let request = self.pending {
                self.pending = nil; self.waitingToOpen = false
                let presentation = TrackMenuPresentation(id: request.id, menu: menu)
                self.openMenu = presentation
                request.completion(presentation)
            }
        }
    }
    func values(_ type: String) -> [TrackLabel] { prepared?.values(type) ?? [] }

    /// No chooser rows exist while pending. The first negotiated source inherits an
    /// initial-loading request; any subsequent metadata/source replacement cancels it.
    func requestOpen(completion: @escaping (TrackMenuPresentation?) -> Void = { _ in }) {
        cancelOpen(handoff: false); requestID += 1
        #if DEBUG
        Logger(subsystem: "app.pictureshow", category: "Playback").notice("track-menu-request generation=\(self.cacheRevision) prepared=\(self.prepared != nil)")
        #endif
        if let prepared {
            let presentation = TrackMenuPresentation(id: requestID, menu: prepared)
            openMenu = presentation; completion(presentation)
        } else {
            waitingToOpen = true
            pending = (requestID, sourceID == nil, completion)
        }
    }
    func cancelOpen(handoff: Bool = true) {
        let completion = pending?.completion
        let hadRows = openMenu != nil
        pending = nil; waitingToOpen = false; openMenu = nil
        if handoff && hadRows { dismissalRevision += 1 }
        completion?(nil)
    }
    func accepts(_ presentation: TrackMenuPresentation) -> Bool {
        openMenu?.id == presentation.id && prepared?.generation == presentation.menu.generation
    }
}

struct PlayerOptions: View {
    @ObservedObject var session: PlaybackSession
    @ObservedObject private var trackMenus: TrackLabelCache
    @Environment(\.dismiss) private var dismiss
    let kind: String
    let done: () -> Void
    @State private var menu: PreparedTrackMenu?
    @State private var presentation: TrackMenuPresentation?
    @State private var audio: Int?
    @State private var subtitle: Int?
    #if os(tvOS)
    @FocusState private var pendingCancelFocused: Bool
    #endif
    init(session: PlaybackSession, kind: String, done: @escaping () -> Void) {
        self.session = session; self.kind = kind; self.done = done
        _trackMenus = ObservedObject(wrappedValue: session.trackLabels)
        _presentation = State(initialValue: session.trackLabels.openMenu)
        _menu = State(initialValue: session.trackLabels.openMenu?.menu ?? session.trackLabels.prepared)
        _audio = State(initialValue: session.decision?.audioIndex)
        _subtitle = State(initialValue: session.decision?.subtitleIndex)
    }
    var body: some View {
        let _ = session.diagnostics.record("phonePlayerOptions.body")
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
                    if menu == nil {
                        ProgressView("Preparing audio and subtitles…").accessibilityIdentifier("preparingTracks")
                        #if os(tvOS)
                        Button("Cancel", action: closePanel)
                            .accessibilityIdentifier("cancelPendingTracks")
                            .focused($pendingCancelFocused)
                        #endif
                    } else {
                        if session.changingTracks { ProgressView("Applying tracks…").accessibilityIdentifier("applyingTracks") }
                        #if DEBUG
                        if ProcessInfo.processInfo.environment["PICTURESHOW_TEST_TRACK_MENU_GATE"] != nil {
                            Button("Replace fixture source") { Task { await session.selectTrack(type: "Audio", index: session.decision?.audioIndex == 13 ? 14 : 13) } }
                                .accessibilityIdentifier("fixtureReplaceTrackSourceInPanel")
                        }
                        #endif
                        Section("Audio") {
                            track("Off", index: -1, type: "Audio", selected: audio == -1)
                            ForEach(menu?.audio ?? []) { track(trackTitle($0), index: $0.id, type: "Audio", selected: audio == $0.id) }
                        }
                        Section("Subtitles") {
                            track("Off", index: -1, type: "Subtitle", selected: subtitle == -1)
                            ForEach(menu?.subtitles ?? []) { track(trackTitle($0), index: $0.id, type: "Subtitle", selected: subtitle == $0.id) }
                            Text(session.subtitleTimingExplanation).font(.caption)
                            if let status = session.subtitleStatus { Text(status).font(.caption).accessibilityIdentifier("subtitleLoadingStatus") }
                        }
                        if let error = session.extrasError { Text(error) }
                    }
                }
            }
            .pictureshowNavigationTitle(kind == "seek" ? "Seek" : kind == "chapters" ? "Chapters" : trackPanelTitle)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done", action: closePanel).accessibilityIdentifier("playerOptionsDone") } }
        }
        #if os(tvOS)
        .onExitCommand { closePanel() }
        #endif
        .onChange(of: session.decision?.audioIndex) { _, value in
            if menu?.generation == session.trackLabels.cacheRevision { audio = value }
        }
        .onChange(of: session.decision?.subtitleIndex) { _, value in
            if menu?.generation == session.trackLabels.cacheRevision { subtitle = value }
        }
        .onChange(of: trackMenus.openMenu) { _, value in
            if let value {
                menu = value.menu; presentation = value
                audio = session.decision?.audioIndex; subtitle = session.decision?.subtitleIndex
            } else if presentation != nil { closePanel() }
        }
        .onChange(of: trackMenus.waitingToOpen) { _, waiting in
            if !waiting && trackMenus.openMenu == nil && menu == nil { closePanel() }
        }
        #if os(tvOS)
        .onAppear { if kind == "tracks" && menu == nil { pendingCancelFocused = true } }
        #endif
    }
    private func trackTitle(_ value: TrackLabel) -> String {
        #if os(tvOS)
        return value.menuTitle
        #else
        return value.title
        #endif
    }
    private var trackPanelTitle: String {
        #if os(tvOS)
        return "Tracks"
        #else
        return "Audio and subtitles"
        #endif
    }
    private func closePanel() { done(); dismiss() }
    private func track(_ title: String, index: Int, type: String, selected: Bool) -> some View {
        Button {
            #if DEBUG
            Logger(subsystem: "app.pictureshow", category: "Playback").notice("Track action type=\(type, privacy: .public) index=\(index)")
            #endif
            // A native callback can outlive the rows that supplied it. Check before
            // handing selection to the viewing, then close through the sheet scope.
            guard let presentation, session.trackLabels.accepts(presentation) else { closePanel(); return }
            Task {
                guard session.trackLabels.accepts(presentation) else { return }
                closePanel()
                await session.selectTrack(type: type, index: index, menuGeneration: presentation.menu.generation)
            }
        } label: {
            HStack { Text(title); Spacer(); if selected { Image(systemName: "checkmark").accessibilityHidden(true) } }.minimumTouchTarget()
        }
            .accessibilityValue(selected ? "Selected" : "").accessibilityIdentifier("\(type.lowercased())-\(index)")
    }
    private func currentChapter(_ index: Int) -> Bool {
        session.chapters[index].startTicks <= session.positionTicks && (index + 1 == session.chapters.count || session.chapters[index + 1].startTicks > session.positionTicks)
    }
}

struct NativePlayer: UIViewControllerRepresentable {
    @ObservedObject var session: PlaybackSession
    var close: () -> Void
    var openPendingTracks: () -> Void
    @ObservedObject private var trackMenus: TrackLabelCache
    init(session: PlaybackSession, close: @escaping () -> Void, openPendingTracks: @escaping () -> Void = {}) {
        self.session = session; self.close = close; self.openPendingTracks = openPendingTracks; trackMenus = session.trackLabels
    }
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
        controller.installInformation(session: session)
        controller.installTrackMenuCancellation(session.trackLabels)
        #endif
        updateUIViewController(controller, context: context)
        return controller
    }
    func updateUIViewController(_ controller: PictureshowPlayerViewController, context: Context) {
        session.diagnostics.record("nativePlayer.snapshot")
        context.coordinator.close = close
        let subtitleSelection = session.externalSubtitles.revision
        controller.subtitleDidDisplay = { [weak session] in session?.externalSubtitles.didDisplayVisibleText(selection: subtitleSelection) }
        controller.showSubtitle(session.subtitleCue, controlsVisible: session.controlsVisible, preferences: session.preferences)
        #if os(tvOS)
        let continuation = session.nextCountdown != nil ? session.nextItem : nil
        context.coordinator.updateContinuation(continuation, countdown: session.nextCountdown)
        // Keep the early loading action while its sheet is open. Rebuild the
        // complete native menu after Back/selection dismisses that sheet.
        let preparedSignature = session.trackLabels.openMenu == nil ? session.trackLabels.prepared?.generation ?? -1 : -1
        let signature = "\(session.trackLabels.cacheRevision)|\(preparedSignature)|\(continuation?.id ?? "")|\(session.item.id)|\(session.decision?.playSessionId ?? "")|\(session.decision?.audioIndex ?? -999)|\(session.decision?.subtitleIndex ?? -999)|\(session.chapters.count)|\(session.changingTracks)"
        if context.coordinator.menuSignature != signature {
            context.coordinator.menuSignature = signature
            session.diagnostics.record("nativePlayer.menuBuilt")
            func tracks(_ type: String) -> UIMenuElement {
                let selected = type == "Audio" ? session.decision?.audioIndex : session.decision?.subtitleIndex
                if let prepared = session.trackLabels.prepared {
                    // AVKit's transport can omit a menu whose only child is a
                    // deferred element. Present complete prepared rows directly;
                    // their generation still guards a late native action.
                    func action(_ title: String, index: Int) -> UIAction {
                        UIAction(title: title, identifier: .init("\(type.lowercased())-\(index)"), state: selected == index ? .on : .off) { _ in
                            Task { @MainActor in
                                guard session.trackLabels.prepared?.generation == prepared.generation,
                                      session.error == nil, !session.terminal else { return }
                                session.trackLabels.cancelOpen()
                                await session.selectTrack(type: type, index: index, menuGeneration: prepared.generation)
                            }
                        }
                    }
                    return UIMenu(title: type == "Audio" ? "Audio" : "Subtitles",
                                  image: UIImage(systemName: type == "Audio" ? "speaker.wave.2" : "captions.bubble"),
                                  options: .singleSelection,
                                  children: [action("Off", index: -1)] + prepared.values(type).map { action($0.menuTitle, index: $0.id) })
                }
                // AVKit ignores a deferred child of its transport UIMenu: the
                // early menu never invokes requestOpen. One native action opens
                // a focusable loading sheet, which mounts complete rows later.
                return UIAction(title: type == "Audio" ? "Audio" : "Subtitles",
                                image: UIImage(systemName: type == "Audio" ? "speaker.wave.2" : "captions.bubble"),
                                identifier: .init("pending\(type)")) { _ in
                    Task { @MainActor in
                        guard session.error == nil, !session.terminal else { return }
                        openPendingTracks()
                    }
                }
            }
            let chapters = UIMenu(title: "Chapters", image: UIImage(systemName: "list.bullet"), children: session.chapters.enumerated().map { index, chapter in
                UIAction(title: chapter.name.isEmpty ? "Chapter \(index + 1)" : chapter.name, subtitle: clock(chapter.startTicks)) { _ in session.seek(toTicks: chapter.startTicks) }
            })
            let stop = UIAction(title: "Back to details", image: UIImage(systemName: "stop.fill"), identifier: UIAction.Identifier("closePlayer")) { _ in close() }
            // Contextual actions are hidden while AVKit's transport is visible. Keep
            // the same continuation reachable in the native bar during that state.
            let continuationMenus: [UIMenuElement] = context.coordinator.continuationTransportActions
            controller.transportBarCustomMenuItems = continuationMenus
                + [tracks("Audio"), tracks("Subtitle")] + (session.chapters.isEmpty ? [] : [chapters]) + [stop]
        }
        var contextActions: [UIAction] = []
        if let segment = session.skipSegment, session.preferences.segmentModes[segment.type] != .automatic {
            contextActions.append(UIAction(title: "Skip \(segment.type.rawValue)", identifier: .init("skipSegment")) { _ in session.skip() })
        }
        contextActions += context.coordinator.continuationContextActions
        let actionSignature = (session.skipSegment?.id ?? "") + "|" + contextActions.map { $0.identifier.rawValue }.joined(separator: "|")
            + "|" + (session.nextCountdown.map(String.init) ?? "")
        if context.coordinator.actionSignature != actionSignature {
            context.coordinator.actionSignature = actionSignature
            // AVKit owns the visible pills. Republish their retained actions
            // when the title changes, without replacing the focused action.
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
        var continuationContextActions: [UIAction] = []
        func updateContinuation(_ next: MediaItem?, countdown: Int?) {
            guard let next, let countdown else {
                continuationID = nil; continuationTransportActions = []; continuationContextActions = []
                return
            }
            if continuationID != next.id {
                continuationID = next.id
                func play(_ identifier: String) -> UIAction {
                    UIAction(title: identifier == "playNext" ? "Next (\(countdown))" : "Play next: \(next.name) (\(countdown))", image: UIImage(systemName: "forward.end.fill"), identifier: .init(identifier)) { [weak self] _ in
                        guard let self, self.session.nextItem?.id == next.id, self.session.nextCountdown != nil else { return }
                        Logger(subsystem: "app.pictureshow", category: "Playback").notice("Manual next activated item=\(next.id, privacy: .public) ticks=\(self.session.positionTicks)")
                        Task { await self.session.advance() }
                    }
                }
                func cancel(_ identifier: String) -> UIAction {
                    UIAction(title: identifier == "cancelNext" ? "Cancel" : "Cancel next episode", identifier: .init(identifier)) { [weak self] _ in self?.session.cancelContinuation() }
                }
                continuationTransportActions = [play("playNextTransport"), cancel("cancelNextTransport")]
                continuationContextActions = [play("playNext"), cancel("cancelNext")]
                actionSignature = "" // A different episode may have the same action identifiers.
            }
            // Keep the narrow contextual pill readable; the transport action
            // retains the episode name. Reuse actions across countdown ticks.
            continuationContextActions.first?.title = "Next (\(countdown))"
            #if os(tvOS)
            continuationTransportActions.first?.title = "Play next: \(next.name) (\(countdown))"
            #endif
        }
        func playerViewControllerShouldDismiss(_ controller: AVPlayerViewController) -> Bool {
            #if DEBUG
            Logger(subsystem: "app.pictureshow", category: "Playback").notice("Native dismissal requested")
            #endif
            close(); return false
        }
        func playerViewController(_ controller: AVPlayerViewController, willTransitionToVisibilityOfTransportBar visible: Bool, with coordinator: AVPlayerViewControllerAnimationCoordinator) {
            #if DEBUG
            Logger(subsystem: "app.pictureshow", category: "Playback").notice("Native transport visibility=\(visible)")
            #endif
            Task { @MainActor in
                session.controlsVisible = visible
                if !visible && !session.panelOpen { session.trackLabels.cancelOpen() }
            }
        }
        func playerViewController(_ controller: AVPlayerViewController, timeToSeekAfterUserNavigatedFrom oldTime: CMTime, to targetTime: CMTime) -> CMTime {
            // AVKit asks for this target synchronously on its UI callback path.
            // The delegate requirement is not annotated MainActor in the SDK.
            MainActor.assumeIsolated {
                CMTime(seconds: session.nativeSeekTarget(from: oldTime.seconds, to: targetTime.seconds), preferredTimescale: 600)
            }
        }
        #endif
    }
}

/// Optional plain-text captions live in AVKit's video overlay, never in its asset.
/// Loading failure cannot hold the transport or change its media clock.
final class PictureshowPlayerViewController: AVPlayerViewController {
    var subtitleDidDisplay: (() -> Void)?
    private let caption = UILabel()
    private var controlsVisible = false
    private var captionPreferences = PlaybackPreferences()
    private(set) var captionObserverRegistrations = 0
    private var observesCaptionAppearance = false
    private func observeCaptionAppearance() {
        guard !observesCaptionAppearance else { return }
        observesCaptionAppearance = true; captionObserverRegistrations += 1
        CFNotificationCenterAddObserver(CFNotificationCenterGetLocalCenter(), Unmanaged.passUnretained(self).toOpaque(), { _, observer, _, _, _ in
            guard let observer else { return }
            // MediaAccessibility delivers a CF notification; marshal UIKit work.
            let controller = Unmanaged<PictureshowPlayerViewController>.fromOpaque(observer).takeUnretainedValue()
            Task { @MainActor [weak controller] in
                guard let controller else { return }
                controller.showSubtitle(controller.caption.text, controlsVisible: controller.controlsVisible, preferences: controller.captionPreferences)
            }
        }, kMACaptionAppearanceSettingsChangedNotification, nil, .deliverImmediately)
    }
    deinit {
        if observesCaptionAppearance {
            CFNotificationCenterRemoveObserver(CFNotificationCenterGetLocalCenter(), Unmanaged.passUnretained(self).toOpaque(), CFNotificationName(rawValue: kMACaptionAppearanceSettingsChangedNotification), nil)
        }
    }
    #if os(tvOS)
    private weak var trackMenus: TrackLabelCache?
    private var trackMenuObservation: AnyCancellable?
    func installTrackMenuCancellation(_ menus: TrackLabelCache) {
        trackMenus = menus
        trackMenuObservation = menus.$dismissalRevision.dropFirst().sink { [weak self] _ in
            guard let self else { return }
            // AVKit owns the native dismissal/focus handoff. Retained action
            // callbacks are fenced even if that transition is still in flight.
            self.presentedViewController?.dismiss(animated: true)
            UIMenuSystem.main.setNeedsRebuild()
        }
    }
    override func pressesBegan(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        #if DEBUG
        if ProcessInfo.processInfo.environment["PICTURESHOW_SEEK_DIAGNOSTICS"] == "1" {
            for press in presses {
                let direction: String
                switch press.type {
                case .leftArrow: direction = "left"
                case .rightArrow: direction = "right"
                case .select: direction = "select"
                case .playPause: direction = "playPause"
                default: continue
                }
                let eventMs = Int64(press.timestamp * 1_000)
                let receivedMs = Int64(ProcessInfo.processInfo.systemUptime * 1_000)
                Logger(subsystem: "app.pictureshow", category: "Playback").notice("Native remote keyDown type=\(direction, privacy: .public) eventMs=\(eventMs) receivedMs=\(receivedMs)")
            }
        }
        #endif
        if presses.contains(where: { $0.type == .menu }) { trackMenus?.cancelOpen() }
        super.pressesBegan(presses, with: event)
    }
    private(set) var informationVisible = false
    func setInformationVisible(_ visible: Bool) {
        guard informationVisible != visible else { return }
        informationVisible = visible
        viewIfLoaded?.setNeedsLayout()
    }
    func installInformation(session: PlaybackSession) {
        let info = PlaybackInformationController(rootView: PlaybackInformation(session: session))
        info.title = "Pictureshow"
        info.preferredContentSize = CGSize(width: 1200, height: 250)
        info.onVisibilityChange = { [weak self, weak session] visible in
            self?.setInformationVisible(visible)
            session?.panelOpen = visible
        }
        customInfoViewControllers = [info]
    }
    #endif
    override func viewDidLoad() {
        super.viewDidLoad()
        observeCaptionAppearance()
        caption.numberOfLines = 0; caption.textAlignment = .center
        caption.textColor = .white; caption.isUserInteractionEnabled = false
        caption.isAccessibilityElement = false // Clock/cue ticks are not announcements.
        contentOverlayView?.addSubview(caption)
    }
    func showSubtitle(_ text: String?, controlsVisible: Bool, preferences: PlaybackPreferences) {
        loadViewIfNeeded()
        captionPreferences = preferences
        caption.text = text; caption.isHidden = text == nil
        self.controlsVisible = controlsVisible
        #if os(tvOS)
        let base: CGFloat = 34
        #else
        let base: CGFloat = 22
        #endif
        caption.font = UIFontMetrics(forTextStyle: .body).scaledFont(for: .systemFont(ofSize: base * CGFloat(preferences.subtitleSize.scale ?? Double(MACaptionAppearanceGetRelativeCharacterSize(.user, nil)))))
        caption.textColor = UIColor(cgColor: MACaptionAppearanceCopyForegroundColor(.user, nil).takeRetainedValue()).withAlphaComponent(MACaptionAppearanceGetForegroundOpacity(.user, nil))
        caption.backgroundColor = preferences.subtitleBackground == .system
            ? UIColor(cgColor: MACaptionAppearanceCopyBackgroundColor(.user, nil).takeRetainedValue()).withAlphaComponent(MACaptionAppearanceGetBackgroundOpacity(.user, nil))
            : preferences.subtitleBackground == .box ? UIColor.black.withAlphaComponent(0.8) : .clear
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
        #if os(tvOS)
        // AVKit's title, transport and Info tabs occupy the lower video band.
        // Keep external text above that band while the native controls appear.
        let inset = informationVisible ? video.height * 0.55
            : controlsVisible ? video.height * 0.4 : min(48, video.height * 0.08)
        #else
        let inset = controlsVisible ? min(240, video.height * 0.35) : min(48, video.height * 0.08)
        #endif
        // Oversized cues retain their preferred type size and truncate within
        // the space above native UI instead of drawing behind the panel.
        let captionHeight = min(video.height * 0.6, video.height - inset,
                                caption.sizeThatFits(CGSize(width: captionWidth, height: .greatestFiniteMagnitude)).height + 8)
        caption.frame = CGRect(x: video.midX - captionWidth / 2, y: max(video.minY, video.maxY - inset - captionHeight), width: captionWidth, height: captionHeight)
        if view.window != nil, !caption.isHidden, captionHeight > 0, !(caption.text ?? "").isEmpty { subtitleDidDisplay?() }
    }
}

#if os(tvOS)
private final class PlaybackInformationController: UIHostingController<PlaybackInformation> {
    var onVisibilityChange: (@MainActor (Bool) -> Void)?
    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        onVisibilityChange?(true)
    }
    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        onVisibilityChange?(false)
    }
}
#endif

private struct PlaybackInformation: View {
    @ObservedObject var session: PlaybackSession
    var body: some View {
        let _ = session.diagnostics.record("expandedInfo.body")
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text(session.item.name).font(.title2).accessibilityAddTraits(.isHeader)
                HStack { Text(session.status).accessibilityIdentifier("playbackMethod"); Text(clock(session.positionTicks)).accessibilityIdentifier("playbackPosition") }
                Text(session.subtitleTimingExplanation).font(.caption)
                if let status = session.subtitleStatus { Text(status).font(.caption).accessibilityIdentifier("subtitleLoadingStatus") }
                SeekOptions(session: session)
                if let warning = session.reportError ?? session.extrasError { Text(warning).foregroundStyle(.orange) }
            }.frame(maxWidth: .infinity, alignment: .leading).padding(24)
                #if os(tvOS)
                .background(Theme.surface.opacity(0.95))
                #endif
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
