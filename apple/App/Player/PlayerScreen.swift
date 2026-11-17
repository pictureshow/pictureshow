// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import AVKit
import SwiftUI
import PictureshowCore

/// Full-screen player. Owns the playback controller for the length of the viewing.
struct PlayerScreen: View {
    @EnvironmentObject private var app: AppModel
    let request: PlayerRequest
    @StateObject private var holder = ControllerHolder()

    var body: some View {
        Group {
            if let controller = holder.controller {
                PlayerContent(controller: controller)
            } else {
                Color.black
            }
        }
        .ignoresSafeArea()
        .background(Color.black)
        .preferredColorScheme(.dark)
        .onAppear {
            if holder.controller == nil {
                let controller = PlaybackController(app: app, request: request)
                holder.controller = controller
                Task { await controller.start() }
            }
        }
        .onDisappear { holder.controller?.close() }
        #if os(iOS)
        .persistentSystemOverlays(.hidden)
        .statusBarHidden()
        #endif
    }
}

@MainActor final class ControllerHolder: ObservableObject {
    @Published var controller: PlaybackController?
}

/// Drawn on every platform over the video: subtitles, the conversion notice, skip and next-episode prompts.
struct PlayerOverlays: View {
    @ObservedObject var controller: PlaybackController
    var controlsVisible: Bool

    var body: some View {
        ZStack {
            if let cue = controller.cue {
                VStack {
                    Spacer()
                    SubtitleText(text: cue, scale: controller.subtitleScale, background: controller.subtitleBackground)
                        .padding(.bottom, controlsVisible ? platformValue(tv: 220, touch: 130) : platformValue(tv: 80, touch: 36))
                        .padding(.horizontal, 40)
                }
                .allowsHitTesting(false)
                .animation(.easeOut(duration: 0.2), value: controlsVisible)
            }
            if controller.isBuffering, controller.error == nil {
                ProgressView().controlSize(.large).tint(.white)
                    .allowsHitTesting(false)
            }
            if let error = controller.error {
                PlayerError(message: error, retry: controller.retry, close: { controller.close(reason: .ERROR_BACK) })
            }
        }
    }
}

struct SubtitleText: View {
    let text: String
    let scale: Double
    let background: SubtitleBackground
    var body: some View {
        Text(text)
            .font(.system(size: platformValue(tv: 46, touch: 20) * scale, weight: .semibold))
            .multilineTextAlignment(.center)
            .foregroundStyle(.white)
            .shadow(color: .black.opacity(background == .none ? 0 : 0.9), radius: 3, x: 0, y: 1)
            .padding(.horizontal, background == .box ? 12 : 0).padding(.vertical, background == .box ? 4 : 0)
            .background(RoundedRectangle(cornerRadius: 6).fill(.black.opacity(background == .box ? 0.75 : 0)))
            .accessibilityLabel("Subtitle: \(text)")
    }
}

struct PlayerError: View {
    let message: String
    let retry: () -> Void
    let close: () -> Void
    var body: some View {
        VStack(spacing: 18) {
            Image(systemName: "exclamationmark.triangle").font(.system(size: 44)).foregroundStyle(.white)
            Text("This video didn't play").font(.title2.weight(.bold)).foregroundStyle(.white)
            Text(message).font(.callout).foregroundStyle(.white.opacity(0.8)).multilineTextAlignment(.center).frame(maxWidth: 640)
            HStack(spacing: 16) {
                Button("Try again", action: retry).primaryAction()
                Button("Close", action: close).secondaryAction()
            }
        }
        .padding(40)
        .background(RoundedRectangle(cornerRadius: 24).fill(.black.opacity(0.75)))
    }
}

/// Explains why the server is converting this video and what would let it play directly.
struct ConversionNoticeCard: View {
    let notice: ConversionNotice
    let dismiss: () -> Void
    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: "arrow.triangle.2.circlepath").font(.title3).foregroundStyle(Theme.accent)
            VStack(alignment: .leading, spacing: 4) {
                Text(notice.title).font(.subheadline.weight(.bold))
                Text(notice.reason).font(.caption)
                Text(notice.fix).font(.caption).foregroundStyle(.white.opacity(0.75))
            }
            .foregroundStyle(.white)
            Spacer(minLength: 0)
            Button(action: dismiss) {
                Image(systemName: "xmark").font(.caption.weight(.bold))
                    #if !os(tvOS)
                    .frame(width: 44, height: 44).contentShape(Rectangle())
                    #endif
            }
                #if os(tvOS)
                .buttonStyle(.bordered)
                #else
                .buttonStyle(.plain).foregroundStyle(.white.opacity(0.8)).padding(-10)
                #endif
                .accessibilityLabel("Dismiss")
        }
        .padding(16)
        .frame(maxWidth: platformValue(tv: 760, touch: 440), alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(.ultraThinMaterial).environment(\.colorScheme, .dark))
        .accessibilityElement(children: .contain)
    }
}

/// "Up next" card with the countdown.
struct NextEpisodeCard: View {
    @EnvironmentObject private var app: AppModel
    @ObservedObject var controller: PlaybackController
    let next: MediaItem
    var body: some View {
        HStack(spacing: 16) {
            ArtworkImage(url: app.client?.artworkURL(account: controller.account, item: next, kind: .wide, width: 480), placeholderSymbol: "tv")
                .frame(width: platformValue(tv: 280, touch: 112), height: platformValue(tv: 158, touch: 63))
                .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
            VStack(alignment: .leading, spacing: 6) {
                if controller.askingStillWatching {
                    // PLAY-30: nothing plays until the viewer answers.
                    Text("Are you still watching?").font(.headline).foregroundStyle(.white)
                    Text("Next: \(next.name)").font(.caption).foregroundStyle(.white.opacity(0.75)).lineLimit(2)
                } else {
                    Text(countdownText).font(.caption.weight(.bold)).textCase(.uppercase).foregroundStyle(Theme.accent)
                    Text(next.name).font(.headline).foregroundStyle(.white).lineLimit(2)
                    Text(Format.episode(next)).font(.caption).foregroundStyle(.white.opacity(0.75))
                }
                HStack(spacing: 12) {
                    if controller.askingStillWatching {
                        Button { controller.keepWatching() } label: { Label("Keep watching", systemImage: "play.fill") }
                            .primaryAction()
                            .accessibilityIdentifier("still-watching-keep")
                        Button("Stop") { controller.close() }
                            .secondaryAction()
                    } else {
                        Button { controller.playNext() } label: { Label("Play now", systemImage: "play.fill") }
                            .primaryAction()
                        Button("Hide") { controller.dismissNextCard() }
                            .secondaryAction()
                    }
                }
                .lineLimit(1)
                .fixedSize(horizontal: true, vertical: false)
                .padding(.top, 4)
            }
        }
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(.ultraThinMaterial).environment(\.colorScheme, .dark))
    }

    private var countdownText: String {
        if let left = controller.nextCountdown, left > 0 { return "Next episode in \(left)" }
        return "Next episode"
    }
}

#if os(tvOS)
/// Apple TV: the system player, so Siri Remote scrubbing, the info panel and audio routes behave as viewers expect.
/// Audio and subtitle choices are the server's tracks (not the stream's), so they go in custom transport-bar menus.
struct PlayerContent: View {
    @ObservedObject var controller: PlaybackController
    @State private var syncRevision = 0
    @State private var findingSubtitles = false

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            SystemPlayer(controller: controller, syncRevision: syncRevision) { findingSubtitles = true }
            PlayerOverlays(controller: controller, controlsVisible: false)
            VStack(alignment: .trailing, spacing: 20) {
                if let notice = controller.notice, !controller.noticeDismissed {
                    ConversionNoticeCard(notice: notice) { controller.noticeDismissed = true }
                        .task { try? await Task.sleep(for: .seconds(12)); controller.noticeDismissed = true }
                }
            }
            .padding(60)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
            if let next = controller.nextEpisode, let countdown = controller.nextCountdown, countdown != 0 {
                NextEpisodeCard(controller: controller, next: next).padding(80)
            }
        }
        .ignoresSafeArea()
        .onReceive(controller.app.syncPlay.$revision) { syncRevision = $0 }
        .sheet(isPresented: $findingSubtitles) {
            SubtitleDownloadView(client: controller.client, account: controller.account, target: controller.subtitleDownloadTarget) { adopted, streams in
                controller.adoptDownloadedSubtitle(adopted, streams: streams)
            }
        }
    }
}

struct SystemPlayer: UIViewControllerRepresentable {
    @ObservedObject var controller: PlaybackController
    /// Redraws the transport bar's Watch together menu when the group changes.
    var syncRevision = 0
    /// SUB-DL-01: opens the subtitle search panel over the player.
    var findSubtitles: () -> Void = {}

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let vc = AVPlayerViewController()
        vc.player = controller.player
        vc.delegate = context.coordinator
        vc.allowsPictureInPicturePlayback = false
        update(vc)
        return vc
    }

    func updateUIViewController(_ vc: AVPlayerViewController, context: Context) { update(vc) }

    func makeCoordinator() -> Coordinator { Coordinator(controller: controller) }

    private func update(_ vc: AVPlayerViewController) {
        vc.transportBarCustomMenuItems = menus()
        var actions: [UIAction] = []
        if let segment = controller.skippable {
            actions.append(UIAction(title: segment.type == .outro ? "Skip Credits" : segment.type == .recap ? "Skip Recap" : "Skip Intro", image: UIImage(systemName: "forward.end")) { [weak controller] _ in controller?.skipSegment() })
        }
        if controller.nextEpisode != nil, controller.nextCountdown != nil {
            if controller.askingStillWatching {
                actions.append(UIAction(title: "Keep Watching", image: UIImage(systemName: "play.fill")) { [weak controller] _ in controller?.keepWatching() })
            } else {
                actions.append(UIAction(title: "Next Episode", image: UIImage(systemName: "forward.frame")) { [weak controller] _ in controller?.playNext() })
            }
        }
        // LIVE-05: the system bar shows Live itself; Go Live returns from a pause or rewind, and a stream that can't
        // time-shift can't be scrubbed or paused.
        if controller.isLive {
            if controller.live.canGoLive {
                actions.append(UIAction(title: "Go Live", image: UIImage(systemName: "dot.radiowaves.forward")) { [weak controller] _ in controller?.goLive() })
            }
            let linear = !controller.live.canTimeShift
            if vc.requiresLinearPlayback != linear { vc.requiresLinearPlayback = linear }
        }
        vc.contextualActions = actions
    }

    private func menus() -> [UIMenuElement] {
        var menus: [UIMenuElement] = []
        let audio = controller.audioStreams
        if audio.count > 1 {
            menus.append(UIMenu(title: "Audio", image: UIImage(systemName: "speaker.wave.2"), options: .singleSelection, children: audio.map { stream in
                UIAction(title: controller.label(stream), state: stream.index == controller.selectedAudio ? .on : .off) { [weak controller] _ in controller?.selectAudio(stream.index) }
            }))
        } else if controller.decision != nil || controller.local != nil, let notice = TrackMenuNotice.empty(type: "Audio", tracks: audio.count) {
            // PLAY-08: say there is nothing to pick rather than leave the viewer looking for the menu.
            menus.append(UIMenu(title: "Audio", image: UIImage(systemName: "speaker.wave.2"), children: [UIAction(title: notice) { _ in }]))
        }
        let subtitles = controller.subtitleStreams
        let target = controller.subtitleDownloadTarget
        var subtitleItems: [UIMenuElement] = []
        if let notice = TrackMenuNotice.empty(type: "Subtitle", tracks: subtitles.count) {
            // Enabled so it draws in the label colour: a disabled item is the faint grey that was hard to read.
            subtitleItems.append(UIAction(title: notice) { _ in })
        } else {
            let off = UIAction(title: "Off", state: controller.selectedSubtitle < 0 ? .on : .off) { [weak controller] _ in controller?.selectSubtitle(-1) }
            subtitleItems.append(UIMenu(options: [.displayInline, .singleSelection], children: [off] + subtitles.map { stream in
                UIAction(title: controller.label(stream), state: stream.index == controller.selectedSubtitle ? .on : .off) { [weak controller] _ in controller?.selectSubtitle(stream.index) }
            }))
        }
        if target.playable {
            subtitleItems.append(UIMenu(options: .displayInline, children: [UIAction(title: "Download subtitles", image: UIImage(systemName: "arrow.down.circle")) { _ in findSubtitles() }]))
        }
        if !subtitles.isEmpty || target.playable || controller.local != nil {
            menus.append(UIMenu(title: "Subtitles", image: UIImage(systemName: "captions.bubble"), children: subtitleItems))
        }
        menus.append(UIAction(title: controller.methodDescription, image: UIImage(systemName: "info.circle"), attributes: .disabled) { _ in })
        // Watch together on Apple TV: the group's state and Leave, reachable from the transport bar with the remote.
        let sync = controller.app.syncPlay!
        if controller.sync != nil, sync.participating {
            let status = UIAction(title: "\(sync.groupName ?? "Group") · \(SyncLabels.phase(sync.phase))", subtitle: SyncLabels.quality(sync.quality), attributes: .disabled) { _ in }
            let wait = UIAction(title: "Wait for me", state: sync.waitForMe ? .on : .off) { _ in sync.setWaitForMe(!sync.waitForMe) }
            let leave = UIAction(title: "Leave group", image: UIImage(systemName: "rectangle.portrait.and.arrow.right"), attributes: .destructive) { _ in sync.leave() }
            menus.append(UIMenu(title: "Watch together", image: UIImage(systemName: "person.2"), children: [status, wait, leave]))
        }
        return menus
    }

    final class Coordinator: NSObject, AVPlayerViewControllerDelegate {
        let controller: PlaybackController
        init(controller: PlaybackController) { self.controller = controller }
        func playerViewControllerShouldDismiss(_ playerViewController: AVPlayerViewController) -> Bool { true }
        func playerViewControllerDidEndDismissalTransition(_ playerViewController: AVPlayerViewController) {
            Task { @MainActor in controller.close() }
        }
    }
}
#else
/// iPhone and iPad: custom controls over the video so the server's audio and subtitle tracks, the
/// conversion notice and the skip prompts sit where a thumb reaches them.
struct PlayerContent: View {
    @ObservedObject var controller: PlaybackController
    @State private var controlsVisible = true
    @State private var hideTask: Task<Void, Never>?
    @State private var scrubbing: Double?
    @State private var showingGroup = false
    @State private var findingSubtitles = false
    @StateObject private var pip = PictureInPictureHost()
    /// Read so a rotation re-evaluates `topClearance`.
    @Environment(\.verticalSizeClass) private var verticalSizeClass

    /// The player draws edge to edge, so in portrait the top bar would sit under the Dynamic Island or notch, where
    /// taps go to the system (OBSERVED: the group chip under the island didn't respond). Landscape has no top inset.
    private var topClearance: CGFloat {
        _ = verticalSizeClass
        let window = UIApplication.shared.connectedScenes.compactMap { ($0 as? UIWindowScene)?.keyWindow }.first
        return max(0, (window?.safeAreaInsets.top ?? 0) - 12)
    }

    var body: some View {
        ZStack {
            VideoSurface(player: controller.player) { pip.attach($0) }
                .ignoresSafeArea()
                .onTapGesture { if !pip.active { toggleControls() } }
                .accessibilityHidden(true)
            if pip.active {
                // TOUCH-15: the picture is in the system window; this screen says so until it comes back.
                VStack(spacing: 10) {
                    Image(systemName: "pip").font(.system(size: 44)).foregroundStyle(.secondary)
                    Text("Playing in picture in picture").font(.headline)
                    Text("Tap the window and choose expand to come back here.")
                        .font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center)
                }
                .padding(32)
                .allowsHitTesting(false)
                .accessibilityElement(children: .combine)
            }
            if let route = controller.externalRoute {
                // The picture is on the receiver; say where, instead of leaving a black screen (CAST-07).
                VStack(spacing: 10) {
                    Image(systemName: "airplayvideo").font(.system(size: 44)).foregroundStyle(.secondary)
                    Text("Playing on \(route)").font(.headline)
                    Text("Use the controls here, or choose this \(UIDevice.current.model) in AirPlay to bring it back.")
                        .font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center)
                }
                .padding(32)
                .allowsHitTesting(false)
                .accessibilityElement(children: .combine)
            }
            if !pip.active {
                PlayerOverlays(controller: controller, controlsVisible: controlsVisible)
            }
            if controlsVisible && !pip.active { controls.transition(.opacity) }
            if !pip.active {
            VStack(alignment: .trailing, spacing: 12) {
                Spacer()
                if let segment = controller.skippable {
                    Button { controller.skipSegment() } label: {
                        Label(segment.type == .outro ? "Skip credits" : segment.type == .recap ? "Skip recap" : "Skip intro", systemImage: "forward.end.fill")
                    }
                    .primaryAction()
                }
                if let next = controller.nextEpisode, let countdown = controller.nextCountdown, countdown != 0 {
                    NextEpisodeCard(controller: controller, next: next)
                }
            }
            .padding(.trailing, 24)
            .padding(.bottom, controlsVisible ? 110 : 40)
            .frame(maxWidth: .infinity, alignment: .trailing)
            }
        }
        .background(Color.black)
        .animation(.easeInOut(duration: 0.2), value: controlsVisible)
        // TOUCH-11: Escape on a keyboard and the VoiceOver escape gesture leave the player whether or not the controls
        // show; an open sheet or menu takes Escape first, because it is presented above this view.
        .accessibilityAction(.escape) { controller.close() }
        .background {
            Button("Close player") { controller.close() }
                .keyboardShortcut(.cancelAction)
                .opacity(0)
                .accessibilityHidden(true)
        }
        .sheet(isPresented: $findingSubtitles) {
            SubtitleDownloadView(client: controller.client, account: controller.account, target: controller.subtitleDownloadTarget) { adopted, streams in
                controller.adoptDownloadedSubtitle(adopted, streams: streams)
            }
        }
        // Presented from the whole screen, not the top bar, so hiding the controls can't take the sheet down with them.
        .sheet(isPresented: $showingGroup) {
            WatchTogetherSheet(sync: controller.app.syncPlay, item: nil).environmentObject(controller.app)
        }
        .onAppear {
            pip.controller = controller
            // AUTH-18: the window keeps Pictureshow in use only while it plays.
            let app = controller.app
            pip.onUse = { active, playing in app.pictureInPictureMoved(active: active, playing: playing) }
            scheduleHide()
        }
        .onChange(of: controller.isPlaying) { scheduleHide() }
        .onChange(of: showingGroup) { scheduleHide() }
        // TOUCH-15: the window follows the gate: no error or loading card, no finished title, nothing on AirPlay.
        .onChange(of: PictureInPictureHost.settings(for: controller), initial: true) { _, settings in pip.update(settings) }
        .onChange(of: pip.active) { _, active in
            if active {
                // Sheets and menus close on entering the window; the controls come back with the full player.
                findingSubtitles = false
                showingGroup = false
                scrubbing = nil
                hideTask?.cancel()
                controlsVisible = false
            } else if !controller.finished {
                controlsVisible = true
                scheduleHide()
            }
        }
    }

    /// TOUCH-15: More offers Picture in picture while the gate and AVKit allow it.
    private var pictureInPictureOffered: Bool {
        #if DEBUG
        // The iOS Simulator reports no picture-in-picture (OBSERVED on iOS 18 and 27 runtimes), so captures of the menu
        // can ask to see it with the gate alone: PICTURESHOW_PIP_PREVIEW=1. Choosing it then only plays the in-app part.
        if PictureInPictureHost.preview { return PictureInPicture.allowed(controller.pictureInPictureState(tvInterface: false, deviceSupports: true)) }
        #endif
        return PictureInPictureHost.settings(for: controller).allowed && pip.possible
    }

    private var moreMenu: some View {
        Menu {
            Button {
                #if DEBUG
                if PictureInPictureHost.preview { pip.willStart(); return }
                #endif
                pip.start()
            } label: { Label("Picture in picture", systemImage: "pip.enter") }
                .accessibilityIdentifier("player-pip")
        } label: {
            Image(systemName: "ellipsis.circle").font(.title3).frame(width: 44, height: 44)
        }
        .accessibilityLabel("More")
        .simultaneousGesture(TapGesture().onEnded { hideTask?.cancel() })
    }

    private var controls: some View {
        ZStack {
            LinearGradient(colors: [.black.opacity(0.7), .clear, .clear, .black.opacity(0.8)], startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea()
                .allowsHitTesting(false)
            VStack(spacing: 0) {
                topBar
                    .padding(.top, topClearance)
                Spacer()
                centerButtons
                Spacer()
                if let notice = controller.notice, !controller.noticeDismissed {
                    ConversionNoticeCard(notice: notice) { controller.noticeDismissed = true }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.bottom, 12)
                }
                if controller.isLive {
                    LiveTimeline(controller: controller) { scheduleHide() }
                } else {
                    scrubber
                }
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 12)
            .foregroundStyle(.white)
        }
    }

    private var topBar: some View {
        HStack(alignment: .center, spacing: 16) {
            Button { controller.close() } label: {
                Image(systemName: "xmark").font(.title3.weight(.semibold)).frame(width: 44, height: 44)
            }
            .accessibilityLabel("Close player")
            VStack(alignment: .leading, spacing: 2) {
                Text(controller.title).font(.headline).lineLimit(1)
                if let subtitle = controller.subtitle { Text(subtitle).font(.caption).opacity(0.8).lineLimit(1) }
            }
            // The title keeps its room; the group chip shortens to fit what's left.
            .layoutPriority(1)
            Spacer()
            if controller.sync != nil, controller.app.syncPlay.participating {
                SyncGroupChip(sync: controller.app.syncPlay) { showingGroup = true }
            }
            trackMenu
            if pictureInPictureOffered { moreMenu }
            // SYN-10: AirPlay can't follow a group's timeline; the viewer is asked to leave the group first.
            if controller.sync != nil && controller.app.syncPlay.participating {
                SyncAirPlayButton(sync: controller.app.syncPlay) { [weak controller] in controller?.airPlayAfterLeaving() }
            } else {
                RoutePicker(open: controller.routePickerPending) { controller.routePickerPending = false }
                    .frame(width: 44, height: 44).accessibilityLabel("AirPlay")
            }
        }
    }

    private var trackMenu: some View {
        Menu {
            let audio = controller.audioStreams
            if audio.count > 1 {
                Picker("Audio", selection: Binding(get: { controller.selectedAudio ?? -1 }, set: { controller.selectAudio($0) })) {
                    ForEach(audio) { Text(controller.label($0)).tag($0.index) }
                }
                .pickerStyle(.menu)
            } else if controller.decision != nil || controller.local != nil, let notice = TrackMenuNotice.empty(type: "Audio", tracks: audio.count) {
                Section("Audio") { MenuNotice(text: notice) }
            }
            if let notice = TrackMenuNotice.empty(type: "Subtitle", tracks: controller.subtitleStreams.count) {
                // PLAY-08: one line instead of a lone Off.
                Section("Subtitles") { MenuNotice(text: notice) }
            } else {
                Picker("Subtitles", selection: Binding(get: { controller.selectedSubtitle }, set: { controller.selectSubtitle($0) })) {
                    Text("Off").tag(-1)
                    ForEach(controller.subtitleStreams) { Text(controller.label($0)).tag($0.index) }
                }
                .pickerStyle(.menu)
            }
            if controller.subtitleDownloadTarget.playable {
                Button { findingSubtitles = true } label: { Label("Download subtitles…", systemImage: "arrow.down.circle") }
            }
            Section(controller.methodDescription) {}
                // An open menu must not vanish with the controls: hiding waits for the next tap on the video.
                .onAppear { hideTask?.cancel() }
        } label: {
            Image(systemName: "captions.bubble").font(.title3).frame(width: 44, height: 44)
        }
        .accessibilityLabel("Audio and subtitles")
        .simultaneousGesture(TapGesture().onEnded { scheduleHide() })
    }

    @ViewBuilder private var centerButtons: some View {
        // LIVE-05: without time shift there is nothing to pause or seek; at the live point there is no forward skip.
        if controller.isLive && !controller.live.canTimeShift {
            Color.clear.frame(height: 72)
        } else {
            seekButtons
        }
    }

    private var seekButtons: some View {
        HStack(spacing: 56) {
            Button { controller.skip(by: -Double(controller.app.preferences.skipBackSeconds)); scheduleHide() } label: {
                Image(systemName: "gobackward.\(controller.app.preferences.skipBackSeconds)").font(.system(size: 30, weight: .medium))
            }
            .accessibilityLabel("Back \(controller.app.preferences.skipBackSeconds) seconds")
            Button { controller.togglePlay(); scheduleHide() } label: {
                Image(systemName: controller.isPlaying ? "pause.fill" : "play.fill").font(.system(size: 48, weight: .semibold)).frame(width: 72, height: 72)
            }
            .accessibilityLabel(controller.isPlaying ? "Pause" : "Play")
            Button { controller.skip(by: Double(controller.app.preferences.skipForwardSeconds)); scheduleHide() } label: {
                Image(systemName: "goforward.\(controller.app.preferences.skipForwardSeconds)").font(.system(size: 30, weight: .medium))
            }
            .accessibilityLabel("Forward \(controller.app.preferences.skipForwardSeconds) seconds")
            .disabled(controller.isLive && (controller.live.atEdge || controller.live.expired))
            .opacity(controller.isLive && (controller.live.atEdge || controller.live.expired) ? 0.35 : 1)
        }
        .buttonStyle(.plain)
        .opacity(controller.isBuffering && !controller.isPlaying ? 0 : 1)
    }

    private var scrubber: some View {
        let shown = scrubbing ?? controller.position
        let total = max(controller.duration, 1)
        return VStack(spacing: 6) {
            Slider(value: Binding(get: { shown }, set: { scrubbing = $0 }), in: 0...total) { editing in
                if !editing, let target = scrubbing { controller.seek(to: target); scrubbing = nil; scheduleHide() }
                if editing { hideTask?.cancel() }
            }
            .tint(Theme.accent)
            .accessibilityLabel("Position")
            .accessibilityValue("\(Format.clock(shown)) of \(Format.clock(controller.duration))")
            HStack {
                Text(Format.clock(shown))
                Spacer()
                Text("-" + Format.clock(max(0, controller.duration - shown)))
            }
            .font(.caption.monospacedDigit())
            .opacity(0.85)
        }
    }

    private func toggleControls() {
        controlsVisible.toggle()
        scheduleHide()
    }

    private func scheduleHide() {
        hideTask?.cancel()
        // PLAY-04: 10 s idle, never while paused or with VoiceOver on.
        guard controlsVisible, controller.isPlaying, !UIAccessibility.isVoiceOverRunning else { return }
        hideTask = Task {
            try? await Task.sleep(for: .seconds(10))
            // The group sheet and the AirPlay question are anchored in the controls; they stay while either is open.
            if !Task.isCancelled, scrubbing == nil, !showingGroup, controller.app.syncPlay.airPlayConsent == nil { controlsVisible = false }
        }
    }
}

/// AVPlayerLayer-backed view.
struct VideoSurface: UIViewRepresentable {
    let player: AVPlayer
    /// Hands the layer to picture-in-picture (TOUCH-15), which shrinks this same layer into the system window.
    var attached: (AVPlayerLayer) -> Void = { _ in }
    func makeUIView(context: Context) -> PlayerLayerView {
        let view = PlayerLayerView()
        view.playerLayer.player = player
        view.playerLayer.videoGravity = .resizeAspect
        view.backgroundColor = .black
        attached(view.playerLayer)
        return view
    }
    func updateUIView(_ view: PlayerLayerView, context: Context) {}

    final class PlayerLayerView: UIView {
        override class var layerClass: AnyClass { AVPlayerLayer.self }
        var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    }
}

struct RoutePicker: UIViewRepresentable {
    /// Opens the system route list once (after leaving a group for AirPlay); `opened` clears the request.
    var open = false
    var opened: () -> Void = {}
    func makeUIView(context: Context) -> AVRoutePickerView {
        let view = AVRoutePickerView()
        view.tintColor = .white
        view.prioritizesVideoDevices = true
        return view
    }
    func updateUIView(_ view: AVRoutePickerView, context: Context) {
        guard open else { return }
        DispatchQueue.main.async {
            // AVRoutePickerView has no API to open its list; its own button does that.
            (view.subviews.first { $0 is UIButton } as? UIButton)?.sendActions(for: .touchUpInside)
            opened()
        }
    }
}
#endif

#if os(iOS)
/// A line of information in a SwiftUI menu. A bare `Text` there becomes a disabled menu item, which iOS draws in the
/// faint disabled grey (about 2.5:1 on the dark menu); an enabled item draws in the label colour. Choosing it only
/// closes the menu.
private struct MenuNotice: View {
    let text: String
    var body: some View {
        Button {} label: { Text(text) }
            .accessibilityRemoveTraits(.isButton)
    }
}
#endif
