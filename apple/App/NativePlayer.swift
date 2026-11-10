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
    @StateObject private var noticePresentation = ConversionNoticePresentation()
    @State private var closing = false
    @State private var orientationError: String?
    #if os(tvOS)
    @State private var returnToPlaySurface = false
    @State private var foregroundPlayVisible = false
    @FocusState private var foregroundPlayFocused: Bool
    #endif
    @ObservedObject private var trackMenus: TrackLabelCache
    init(session: PlaybackSession, close: @escaping () async -> Void) {
        self.session = session; self.close = close
        self.trackMenus = session.trackLabels
    }
    var body: some View {
        ZStack(alignment: .top) {
            #if os(iOS)
            TouchPlayer(session: session, noticePresentation: noticePresentation, panel: $panel, finish: finish, tracks: { trackMenus.requestOpen() })
            #else
            NativePlayer(session: session, close: finish, openPendingTracks: {
                trackMenus.requestOpen()
                panel = "tracks"
            }, openPanel: { kind in trackMenus.cancelOpen(); panel = kind }, panelKind: panel, closePanel: { panel = nil }, backgroundSurfaceFocus: { surface in
                let ownPlayHadFocus = foregroundPlayVisible && foregroundPlayFocused
                returnToPlaySurface = (surface || ownPlayHadFocus) && !session.panelOpen && !UIAccessibility.isVoiceOverRunning
                // Remount on return so the one-time focus handoff also works
                // after Home while the foreground Play action was focused.
                foregroundPlayVisible = false
                foregroundPlayFocused = false
            }).ignoresSafeArea()
            #endif
            #if os(tvOS)
            foregroundPlayOverlay
            ConversionNoticeBanner(session: session, presentation: noticePresentation, fits: true, obscured: session.panelOpen || session.error != nil, openInfo: { panel = "info" })
                .frame(maxWidth: 780, alignment: .leading).frame(maxWidth: .infinity, alignment: .leading)
                .padding(.top, 36).padding(.horizontal, 48)
            #endif
            if session.waitingForVideo || session.error != nil || session.reportError != nil {
                VStack(spacing: 20) {
                    if session.waitingForVideo {
                        ProgressView(session.status).accessibilityIdentifier("playbackWaiting")
                    }
                    if let error = session.error { Text(error).foregroundStyle(.orange).accessibilityIdentifier("playbackError") }
                    if let error = session.reportError { Text(error) }
                    Button("Back to details", action: finish).accessibilityIdentifier("loadingBack")
                }.padding(32).background(.black.opacity(0.85), in: RoundedRectangle(cornerRadius: 16)).padding(.top, 100)
            }
        }
        .background(.black)
        .announceStatus(session.subtitleStatus)
        .onDisappear { session.beginClosing() }
        .onChange(of: session.terminal) { _, value in if value, session.error == nil { finish() } }
        .onChange(of: scenePhase, initial: true) { _, value in
            if value == .active {
                session.foregrounded()
                #if os(tvOS)
                foregroundPlayVisible = returnToPlaySurface && session.isPaused && !session.panelOpen && !UIAccessibility.isVoiceOverRunning
                returnToPlaySurface = false
                #endif
            } else { session.backgrounded() }
        }
        #if os(tvOS)
        .onChange(of: session.isPaused) { _, paused in if !paused { foregroundPlayVisible = false } }
        .onChange(of: session.panelOpen) { _, open in if open { foregroundPlayVisible = false } }
        #endif
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
        #if os(iOS)
        .sheet(isPresented: Binding(get: { panel != nil }, set: { if !$0 { panel = nil } })) {
            PlayerOptions(session: session, kind: panel ?? "tracks") { panel = nil }
        }
        #endif
    }

    #if os(tvOS)
    @ViewBuilder private var foregroundPlayOverlay: some View {
        if foregroundPlayVisible {
                Button {
                    foregroundPlayVisible = false
                    if session.isPaused { session.togglePlayback() }
                } label: {
                    Label("Play", systemImage: "play.fill").font(.title2).padding(20)
                }
                .buttonStyle(ProminentActionStyle())
                .focused($foregroundPlayFocused)
                .accessibilityIdentifier("foregroundPlay")
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)
                .onAppear { foregroundPlayFocused = true }
                .onChange(of: foregroundPlayFocused) { old, value in
                    if old && !value && scenePhase == .active { foregroundPlayVisible = false }
                }
            }
    }
    #endif

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
    @FocusState private var selectedChapter: Int?
    @AccessibilityFocusState private var readerChapter: Int?
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
            ScrollViewReader { proxy in
            List {
                #if os(tvOS)
                Text(panelTitle).font(.title3.weight(.semibold))
                    .foregroundStyle(.primary).accessibilityAddTraits(.isHeader)
                    .accessibilityIdentifier("playerOptionsHeading")
                    .listRowBackground(Color.clear)
                #endif
                if kind == "seek" {
                    SeekOptions(session: session)
                } else if kind == "speed" {
                    SpeedOptions(session: session, done: closePanel)
                } else if kind == "info" {
                    PlaybackInfoRows(session: session)
                } else if kind == "chapters" {
                    if session.chapters.isEmpty { Text("No chapters are available.") }
                    ForEach(Array(session.chapters.enumerated()), id: \.offset) { index, chapter in
                        Button { session.seek(toTicks: chapter.startTicks); closePanel() } label: {
                            HStack {
                                ChapterArtwork(session: session, index: index)
                                Text(chapter.name.isEmpty ? "Chapter \(index + 1)" : chapter.name)
                                Spacer()
                                #if os(iOS)
                                Text(clock(chapter.startTicks)).monospacedDigit().foregroundStyle(Color.secondary)
                                Image(systemName: "checkmark").foregroundStyle(Theme.accent).opacity(currentChapter(index) ? 1 : 0).frame(width: 20).accessibilityHidden(true)
                                #else
                                Text(clock(chapter.startTicks)).monospacedDigit().foregroundStyle(.secondary)
                                if currentChapter(index) { Image(systemName: "checkmark").foregroundStyle(.primary).accessibilityHidden(true) }
                                #endif
                            }.frame(maxWidth: .infinity, alignment: .leading)
                                .touchSecondaryInk()
                        }
                            .accessibilityValue(currentChapter(index) ? "Current chapter" : "")
                            .focused($selectedChapter, equals: index).accessibilityFocused($readerChapter, equals: index).id(index)
                    }
                } else {
                    if menu == nil {
                        #if os(tvOS)
                        HStack(spacing: 12) {
                            ProgressView().tint(.white)
                            Text("Preparing tracks…").font(.headline).foregroundStyle(.white)
                        }
                        .padding(.horizontal, 18)
                        .listRowBackground(Color.clear)
                        .accessibilityElement(children: .combine)
                        .accessibilityLabel("Preparing audio and subtitles")
                        .accessibilityIdentifier("preparingTracks")
                        #else
                        ProgressView("Preparing audio and subtitles…").accessibilityIdentifier("preparingTracks")
                        #endif
                        #if os(tvOS)
                        Button("Cancel", action: closePanel)
                            .accessibilityIdentifier("cancelPendingTracks")
                            .focused($pendingCancelFocused)
                            .buttonStyle(.borderedProminent)
                            .padding(.horizontal, 18)
                            .listRowBackground(Color.clear)
                        #endif
                    } else {
                        if session.changingTracks { ProgressView("Applying tracks…").accessibilityIdentifier("applyingTracks") }
                        #if DEBUG
                        fixtureReplacementAction
                        #endif
                        Section("Audio") {
                            track("Off", index: -1, type: "Audio", selected: audio == -1)
                            ForEach(menu?.audio ?? []) { track(trackTitle($0), index: $0.id, type: "Audio", selected: audio == $0.id) }
                        }
                        Section {
                            track("Off", index: -1, type: "Subtitle", selected: subtitle == -1)
                            ForEach(menu?.subtitles ?? []) { track(trackTitle($0), index: $0.id, type: "Subtitle", selected: subtitle == $0.id) }
                            Text(String(format: "Subtitle delay: %+.1f s", Double(session.subtitleDelayMs) / 1000)).accessibilityIdentifier("subtitleDelayValue")
                            ViewThatFits(in: .horizontal) {
                                HStack { subtitleDelayButtons }
                                VStack(alignment: .leading) { subtitleDelayButtons }
                            }
                            #if os(iOS)
                            // Each timing action is independent inside this List row.
                            .buttonStyle(.bordered)
                            #endif
                            if let status = session.subtitleStatus { Text(status).font(.caption).accessibilityIdentifier("subtitleLoadingStatus") }
                        } header: { Text("Subtitles") } footer: {
                            Text(session.subtitleTimingAvailable ? "Timing changes apply to this viewing only." : session.subtitleTimingExplanation)
                        }
                        if let error = session.extrasError { Text(error) }
                    }
                }
                #if os(tvOS)
                if kind != "tracks" || menu != nil {
                    Button("Done", action: closePanel).accessibilityIdentifier("playerOptionsDone")
                }
                #endif
            }
            .minimumTouchListRows()
            .accessibilityIdentifier("playerOptionsList")
            #if os(tvOS)
            .contentMargins(.horizontal, 32, for: .scrollContent)
            .toolbar(.hidden, for: .navigationBar)
            #endif
            .onAppear {
                guard kind == "chapters", let index = session.chapters.indices.last(where: { currentChapter($0) }) else { return }
                proxy.scrollTo(index, anchor: .center); selectedChapter = index; readerChapter = index
            }
            #if os(iOS)
            .pictureshowNavigationTitle(panelTitle)
            .toolbar {
                if kind != "tracks" || menu != nil {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Done", action: closePanel).accessibilityIdentifier("playerOptionsDone")
                    }
                }
            }
            #endif
            }
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
    #if DEBUG
    @ViewBuilder private var fixtureReplacementAction: some View {
        if ProcessInfo.processInfo.environment["PICTURESHOW_TEST_TRACK_MENU_GATE"] != nil {
            Button("Replace fixture source", action: replaceFixtureSource)
                .accessibilityIdentifier("fixtureReplaceTrackSourceInPanel")
        }
    }
    private func replaceFixtureSource() {
        let replacementIndex: Int = session.decision?.audioIndex == 13 ? 14 : 13
        Task { await session.selectTrack(type: "Audio", index: replacementIndex) }
    }
    #endif
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
    private var panelTitle: String {
        kind == "seek" ? "Seek" : kind == "chapters" ? "Chapters" : kind == "speed" ? "Speed" : kind == "info" ? "Playback info" : trackPanelTitle
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
            HStack {
                Text(title.replacingOccurrences(of: " · ", with: "\u{00A0}· "))
                Spacer()
                if selected {
                    #if os(tvOS)
                    Image(systemName: "checkmark").foregroundStyle(.primary).accessibilityHidden(true)
                    #else
                    Image(systemName: "checkmark").foregroundStyle(Theme.accent).accessibilityHidden(true)
                    #endif
                }
            }.touchSecondaryInk()
        }
            .accessibilityValue(selected ? "Selected" : "").accessibilityIdentifier("\(type.lowercased())-\(index)")
    }
    @ViewBuilder private func timingLabel(_ title: String) -> some View {
        #if os(iOS)
        Text(title).fixedSize().frame(maxWidth: .infinity).minimumTouchTarget()
        #else
        Text(title).fixedSize().minimumTouchTarget()
        #endif
    }
    @ViewBuilder private var subtitleDelayButtons: some View {
        Button { session.adjustSubtitleDelay(by: -100) } label: { timingLabel("Earlier") }.disabled(!session.subtitleTimingAvailable || session.subtitleDelayMs <= -10_000).accessibilityIdentifier("subtitleEarlier")
        Button { session.adjustSubtitleDelay(by: nil) } label: { timingLabel("Reset") }.disabled(!session.subtitleTimingAvailable || session.subtitleDelayMs == 0).accessibilityIdentifier("subtitleReset")
        Button { session.adjustSubtitleDelay(by: 100) } label: { timingLabel("Later") }.disabled(!session.subtitleTimingAvailable || session.subtitleDelayMs >= 10_000).accessibilityIdentifier("subtitleLater")
    }
    private func currentChapter(_ index: Int) -> Bool {
        session.chapters[index].startTicks <= session.positionTicks && (index + 1 == session.chapters.count || session.chapters[index + 1].startTicks > session.positionTicks)
    }
}

struct NativePlayer: UIViewControllerRepresentable {
    @ObservedObject var session: PlaybackSession
    var close: () -> Void
    var openPendingTracks: () -> Void
    var openPanel: (String) -> Void
    var panelKind: String?
    var closePanel: () -> Void
    var backgroundSurfaceFocus: (Bool) -> Void
    var didAppear: () -> Void
    var touchControlsHeight: CGFloat?
    var touchCaptionTrailingInset: CGFloat = 0
    var touchCaptionExclusionFrames: [CGRect] = []
    @ObservedObject private var trackMenus: TrackLabelCache
    init(session: PlaybackSession, close: @escaping () -> Void, openPendingTracks: @escaping () -> Void = {}, openPanel: @escaping (String) -> Void = { _ in }, panelKind: String? = nil, closePanel: @escaping () -> Void = {}, backgroundSurfaceFocus: @escaping (Bool) -> Void = { _ in }, didAppear: @escaping () -> Void = {}, touchControlsHeight: CGFloat? = nil, touchCaptionTrailingInset: CGFloat = 0, touchCaptionExclusionFrames: [CGRect] = []) {
        self.session = session; self.close = close; self.openPendingTracks = openPendingTracks; self.openPanel = openPanel; self.panelKind = panelKind; self.closePanel = closePanel; self.backgroundSurfaceFocus = backgroundSurfaceFocus; self.didAppear = didAppear; self.touchControlsHeight = touchControlsHeight; self.touchCaptionTrailingInset = touchCaptionTrailingInset; self.touchCaptionExclusionFrames = touchCaptionExclusionFrames; trackMenus = session.trackLabels
    }
    func makeUIViewController(context: Context) -> PictureshowPlayerViewController {
        let controller = PictureshowPlayerViewController()
        controller.player = session.player; controller.delegate = context.coordinator
        #if os(tvOS)
        controller.showsPlaybackControls = true
        #else
        controller.showsPlaybackControls = false
        controller.allowsVideoFrameAnalysis = false
        #endif
         controller.videoGravity = .resizeAspect
        controller.allowsPictureInPicturePlayback = false
        // Identify the native video frame without changing accessibility grouping.
        // Touch tests must reveal controls inside this frame, not the app strip.
        controller.view.accessibilityIdentifier = "pictureshowNativePlayer"
        #if os(tvOS)
        controller.playbackControlsIncludeInfoViews = true; controller.transportBarIncludesTitleView = true
        controller.installInformation(session: session)
        controller.installTrackMenuCancellation(session.trackLabels)
        controller.installResumeFocusCapture()
        #endif
        updateUIViewController(controller, context: context)
        return controller
    }
    func updateUIViewController(_ controller: PictureshowPlayerViewController, context: Context) {
        session.diagnostics.record("nativePlayer.snapshot")
        context.coordinator.close = close
        controller.onDidAppear = didAppear
        controller.touchControlsHeight = touchControlsHeight
        controller.touchCaptionTrailingInset = touchCaptionTrailingInset
        controller.touchCaptionExclusionFrames = touchCaptionExclusionFrames
        let subtitleSelection = session.externalSubtitles.revision
        controller.subtitleDidDisplay = { [weak session] in session?.externalSubtitles.didDisplayVisibleText(selection: subtitleSelection) }
        controller.showSubtitle(session.subtitleCue, controlsVisible: session.controlsVisible, preferences: session.preferences)
        #if os(tvOS)
        controller.view.accessibilityValue = session.controlsVisible ? "Controls visible" : "Controls hidden"
        controller.onBackgroundSurfaceFocus = backgroundSurfaceFocus
        controller.updateOptionsPresentation(kind: panelKind, session: session, close: closePanel)
        let continuation = session.nextCardVisible ? session.nextItem : nil
        context.coordinator.updateContinuation(continuation, countdown: session.nextCountdown, visible: session.nextCardVisible)
        // Keep the early loading action while its sheet is open. Rebuild the
        // complete native menu after Back/selection dismisses that sheet.
        let preparedSignature = session.trackLabels.openMenu == nil ? session.trackLabels.prepared?.generation ?? -1 : -1
        let signature = "\(session.trackLabels.cacheRevision)|\(preparedSignature)|\(continuation?.id ?? "")|\(session.item.id)|\(session.decision?.playSessionId ?? "")|\(session.decision?.audioIndex ?? -999)|\(session.decision?.subtitleIndex ?? -999)|\(session.chapters.count)|\(session.changingTracks)"
        context.coordinator.speedAction?.title = "Speed: \(PlaybackSpeeds.label(session.playbackRate))"
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
            let speed = UIAction(title: "Speed: \(PlaybackSpeeds.label(session.playbackRate))", image: UIImage(systemName: "speedometer"), identifier: .init("playerSpeed")) { _ in openPanel("speed") }
            context.coordinator.speedAction = speed
            let information = UIAction(title: "Playback info", image: UIImage(systemName: "info.circle"), identifier: .init("playerInfo")) { _ in openPanel("info") }
            let stop = UIAction(title: "Back to details", image: UIImage(systemName: "stop.fill"), identifier: UIAction.Identifier("closePlayer")) { _ in close() }
            // Contextual actions are hidden while AVKit's transport is visible. Keep
            // the same continuation reachable in the native bar during that state.
            let continuationMenus: [UIMenuElement] = context.coordinator.continuationTransportActions
            controller.transportBarCustomMenuItems = continuationMenus
                + [tracks("Audio"), tracks("Subtitle")] + (session.chapters.isEmpty ? [] : [chapters]) + [speed, information, stop]
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
            controller.view.setNeedsLayout()
        }
        #endif
    }
    func makeCoordinator() -> Coordinator { Coordinator(session: session, close: close) }
    final class Coordinator: NSObject, AVPlayerViewControllerDelegate {
        var speedAction: UIAction?
        let session: PlaybackSession
        var close: () -> Void
        var menuSignature = "", actionSignature = ""
        init(session: PlaybackSession, close: @escaping () -> Void) { self.session = session; self.close = close }
        #if os(tvOS)
        private var continuationID: String?
        var continuationTransportActions: [UIAction] = []
        var continuationContextActions: [UIAction] = []
        func updateContinuation(_ next: MediaItem?, countdown: Int?, visible: Bool = true) {
            guard let next, visible else {
                continuationID = nil; continuationTransportActions = []; continuationContextActions = []
                return
            }
            if continuationID != next.id {
                continuationID = next.id
                func play(_ identifier: String) -> UIAction {
                    UIAction(title: identifier == "playNext" ? countdown.map { "Next (\($0))" } ?? "Play now" : "Play next: \(next.name)" + (countdown.map { " (\($0))" } ?? ""), image: UIImage(systemName: "forward.end.fill"), identifier: .init(identifier)) { [weak self] _ in
                        guard let self, self.session.nextItem?.id == next.id, self.session.nextCardVisible else { return }
                        Logger(subsystem: "app.pictureshow", category: "Playback").notice("Manual next activated item=\(next.id, privacy: .public) ticks=\(self.session.positionTicks)")
                        Task { await self.session.advance() }
                    }
                }
                func cancel(_ identifier: String) -> UIAction {
                    UIAction(title: identifier == "cancelNext" ? "Cancel" : "Cancel next episode", image: UIImage(systemName: "xmark.circle"), identifier: .init(identifier)) { [weak self] _ in self?.session.cancelContinuation() }
                }
                continuationTransportActions = [play("playNextTransport"), cancel("cancelNextTransport")]
                continuationContextActions = [play("playNext"), cancel("cancelNext")]
                actionSignature = "" // A different episode may have the same action identifiers.
            }
            // Keep the narrow contextual pill readable; the transport action
            // retains the episode name. Reuse actions across countdown ticks.
            continuationContextActions.first?.title = countdown.map { "Next (\($0))" } ?? "Play now"
            #if os(tvOS)
            continuationTransportActions.first?.title = "Play next: \(next.name)" + (countdown.map { " (\($0))" } ?? "")
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
    var onDidAppear: (() -> Void)?
    var touchControlsHeight: CGFloat?
    var touchCaptionTrailingInset: CGFloat = 0
    var touchCaptionExclusionFrames: [CGRect] = []
    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        onDidAppear?()
    }
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
        #if os(tvOS)
        if let resumeFocusObserver { NotificationCenter.default.removeObserver(resumeFocusObserver) }
        #endif
        if observesCaptionAppearance {
            CFNotificationCenterRemoveObserver(CFNotificationCenterGetLocalCenter(), Unmanaged.passUnretained(self).toOpaque(), CFNotificationName(rawValue: kMACaptionAppearanceSettingsChangedNotification), nil)
        }
    }
    #if os(tvOS)
    private var optionsHost: UIHostingController<PlayerOptions>?
    var onBackgroundSurfaceFocus: ((Bool) -> Void)?
    private var resumeFocusObserver: NSObjectProtocol?
    private var lastNativeFocusWasSurface = false
    private func isPlaybackSurface(_ item: UIFocusItem?) -> Bool? {
        guard let focused = item as? UIView, focused.window != nil else { return nil }
        let rect = focused.convert(focused.bounds, to: view)
        return rect.width > view.bounds.width * 0.75 && rect.height > view.bounds.height * 0.75
    }
    override func didUpdateFocus(in context: UIFocusUpdateContext, with coordinator: UIFocusAnimationCoordinator) {
        super.didUpdateFocus(in: context, with: coordinator)
        if let surface = isPlaybackSurface(context.nextFocusedItem), UIApplication.shared.applicationState == .active {
            lastNativeFocusWasSurface = surface
        }
    }
    func installResumeFocusCapture() {
        guard resumeFocusObserver == nil else { return }
        resumeFocusObserver = NotificationCenter.default.addObserver(forName: UIApplication.willResignActiveNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self else { return }
                let surface = self.isPlaybackSurface(UIFocusSystem(for: self.view)?.focusedItem) ?? self.lastNativeFocusWasSurface
                self.onBackgroundSurfaceFocus?(surface)
            }
        }
    }
    func updateOptionsPresentation(kind: String?, session: PlaybackSession, close: @escaping () -> Void) {
        if let kind {
            guard optionsHost == nil, presentedViewController == nil else { return }
            // Present from AVKit itself so UIKit returns remote input and focus
            // to the player, rather than to the outer SwiftUI full-screen cover.
            let host = UIHostingController(rootView: PlayerOptions(session: session, kind: kind, done: close))
            if #available(tvOS 26.0, *) { host.modalPresentationStyle = .formSheet }
            // Keep the player mounted on older tvOS too: a covering fullScreen
            // presentation would make PlayerScreen.onDisappear close viewing.
            else { host.modalPresentationStyle = .overFullScreen }
            optionsHost = host
            present(host, animated: true)
        } else if let host = optionsHost {
            optionsHost = nil
            if !host.isBeingDismissed { host.dismiss(animated: true) }
        }
    }
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
                case .upArrow: direction = "up"
                case .downArrow: direction = "down"
                case .menu: direction = "menu"
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
        let info = PlaybackInformationController(rootView: NativeViewingInformation(session: session))
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
        caption.accessibilityIdentifier = "playerSubtitle"
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
    #if os(iOS)
    static func touchCaptionHeight(text: String?, preferences: PlaybackPreferences, width: CGFloat) -> CGFloat {
        guard let text, !text.isEmpty else { return 0 }
        let font = UIFontMetrics(forTextStyle: .body).scaledFont(for: .systemFont(ofSize: 22 * CGFloat(preferences.subtitleSize.scale ?? Double(MACaptionAppearanceGetRelativeCharacterSize(.user, nil)))))
        return ceil((text as NSString).boundingRect(with: CGSize(width: max(1, width), height: .greatestFiniteMagnitude), options: [.usesLineFragmentOrigin, .usesFontLeading], attributes: [.font: font], context: nil).height) + 8
    }
    #endif
    static func captionHorizontalRange(video: CGRect, overlay: CGRect, trailingExclusion: CGFloat, excludedLeadingEdge: CGFloat? = nil) -> ClosedRange<CGFloat> {
        let left = video.minX + video.width * 0.05
        // Exclude the actual overlay band. A narrow video may already end
        // before the card, so subtracting the card width from it is incorrect.
        let right = min(video.maxX - video.width * 0.05, overlay.maxX - trailingExclusion, excludedLeadingEdge.map { $0 - 12 } ?? overlay.maxX)
        return left...max(left + 1, right)
    }
    #if os(iOS)
    /// Place at full centered width first. Only actual rectangle collisions
    /// constrain captions; a card entirely above them cannot rewrap the cue.
    static func captionFrame(video: CGRect, horizontal: ClosedRange<CGFloat>, bottom: CGFloat, obstacles: [CGRect], heightForWidth: (CGFloat) -> CGFloat) -> CGRect {
        let width = max(1, horizontal.upperBound - horizontal.lowerBound)
        let bottom = min(video.maxY, max(video.minY, bottom))
        let usable = CGRect(x: horizontal.lowerBound, y: video.minY, width: width, height: max(0, bottom - video.minY))
        let occupied = obstacles.filter { !$0.isNull && !$0.isEmpty }.map { $0.insetBy(dx: -12, dy: -12) }
        let preferredHeight = min(video.height * 0.6, max(0, heightForWidth(width)))
        let preferred = CGRect(x: usable.minX, y: max(usable.minY, bottom - preferredHeight), width: width, height: min(preferredHeight, usable.height))
        guard occupied.contains(where: { $0.intersects(preferred) }) else { return preferred }
        // Subtract every occupied rectangle before choosing a placement. Moving
        // around one card must not rewrap the cue back under an earlier notice.
        var regions = [usable]
        for obstacle in occupied {
            regions = regions.flatMap { region -> [CGRect] in
                let overlap = region.intersection(obstacle)
                guard !overlap.isNull, !overlap.isEmpty else { return [region] }
                return [
                    CGRect(x: region.minX, y: region.minY, width: region.width, height: max(0, overlap.minY - region.minY)),
                    CGRect(x: region.minX, y: overlap.maxY, width: region.width, height: max(0, region.maxY - overlap.maxY)),
                    CGRect(x: region.minX, y: region.minY, width: max(0, overlap.minX - region.minX), height: region.height),
                    CGRect(x: overlap.maxX, y: region.minY, width: max(0, region.maxX - overlap.maxX), height: region.height)
                ].filter { $0.width > 0 && $0.height > 0 }
            }
        }
        let minimumWidth = min(150, width)
        let readable = regions.filter { $0.width >= minimumWidth }
        let candidates = readable.isEmpty ? regions : readable
        func fits(_ region: CGRect) -> Bool { heightForWidth(region.width) <= region.height }
        let chosen = candidates.sorted { a, b in
            if fits(a) != fits(b) { return fits(a) }
            let aFull = a.width >= width - 1, bFull = b.width >= width - 1
            if aFull != bFull { return aFull }
            if abs(a.maxY - b.maxY) > 1 { return a.maxY > b.maxY }
            return a.width > b.width
        }.first
        guard let region = chosen else { return .zero }
        let height = max(0, min(video.height * 0.6, region.height, heightForWidth(region.width)))
        return CGRect(x: region.minX, y: region.maxY - height, width: region.width, height: height)
    }
    #endif
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
        let horizontal = Self.captionHorizontalRange(video: video, overlay: overlay.bounds, trailingExclusion: touchCaptionTrailingInset)
        let captionLeft = horizontal.lowerBound
        let captionWidth = horizontal.upperBound - horizontal.lowerBound
        #if os(tvOS)
        // AVKit's title, transport and Info tabs occupy the lower video band.
        // Keep external text above that band while the native controls appear.
        // Contextual Skip/Next/Cancel remain visible after the transport hides.
        // Keep every Ask action and reserve their native lower action lane, too.
        let restingInset = min(48, video.height * 0.08)
        let contextualLaneTop = overlay.bounds.maxY - max(160, overlay.bounds.height * 0.18)
        let contextualInset = max(restingInset, video.maxY - contextualLaneTop)
        let inset = informationVisible ? video.height * 0.55
            : controlsVisible ? video.height * 0.4
            : !contextualActions.isEmpty ? contextualInset : restingInset
        #else
        let inset = controlsVisible ? min(240, video.height * 0.35) : min(48, video.height * 0.08)
        #endif
        // Oversized cues retain their preferred type size and truncate within
        // the space above native UI instead of drawing behind the panel.
        let bottom = min(video.maxY - inset, touchControlsHeight.map { overlay.bounds.maxY - $0 - 12 } ?? video.maxY)
        let captionHeight = max(0, min(video.height * 0.6, bottom - video.minY,
                                caption.sizeThatFits(CGSize(width: captionWidth, height: .greatestFiniteMagnitude)).height + 8))
        #if os(iOS)
        let obstacles = view.window.map { window in touchCaptionExclusionFrames.map { overlay.convert($0, from: window) } } ?? []
        caption.frame = Self.captionFrame(video: video, horizontal: horizontal, bottom: bottom, obstacles: obstacles) { width in
            caption.sizeThatFits(CGSize(width: width, height: .greatestFiniteMagnitude)).height + 8
        }
        #else
        caption.frame = CGRect(x: captionLeft, y: max(video.minY, bottom - captionHeight), width: captionWidth, height: captionHeight)
        #endif
        if view.window != nil, !caption.isHidden, captionHeight > 0, !(caption.text ?? "").isEmpty { subtitleDidDisplay?() }
    }
}

#if os(tvOS)
private final class PlaybackInformationController: UIHostingController<NativeViewingInformation> {
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

private struct NativeViewingInformation: View {
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
                Button { adjust(-10) } label: { seekButtonLabel("Back 10 seconds") }.accessibilityIdentifier("seekBackward")
                Button { adjust(10) } label: { seekButtonLabel("Forward 10 seconds") }.accessibilityIdentifier("seekForward")
            }
            #if os(iOS)
            // This HStack shares one List row. Independent button styles prevent the
            // automatic row action from activating both opposing seek controls.
            .buttonStyle(.borderless)
            #else
            // AVKit hosts this Info tab in a separate focus environment. Use
            // native filled buttons and one focus section so the remote can
            // enter the actions from the tab strip and still read their labels.
            .buttonStyle(.borderedProminent)
            .tint(Theme.accent)
            .focusSection()
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
    @ViewBuilder private func seekButtonLabel(_ title: String) -> some View {
        #if os(tvOS)
        Text(title).minimumTouchTarget().foregroundStyle(Theme.background)
        #else
        Text(title).minimumTouchTarget()
        #endif
    }
    private func adjust(_ delta: Double) {
        // The panel may have stayed open while video advanced. Relative buttons
        // follow the current/pending player position, not the old preview target.
        target = Ticks.seconds(session.seek(by: delta))
    }
}
