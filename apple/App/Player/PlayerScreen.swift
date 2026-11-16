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
                PlayerError(message: error, retry: controller.retry, close: controller.close)
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
            Button(action: dismiss) { Image(systemName: "xmark").font(.caption.weight(.bold)) }
                #if os(tvOS)
                .buttonStyle(.bordered)
                #else
                .buttonStyle(.plain).foregroundStyle(.white.opacity(0.8)).padding(4)
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
                Text(countdownText).font(.caption.weight(.bold)).textCase(.uppercase).foregroundStyle(Theme.accent)
                Text(next.name).font(.headline).foregroundStyle(.white).lineLimit(2)
                Text(Format.episode(next)).font(.caption).foregroundStyle(.white.opacity(0.75))
                HStack(spacing: 12) {
                    Button { controller.playNext() } label: { Label("Play now", systemImage: "play.fill") }
                        .primaryAction()
                    Button("Hide") { controller.dismissNextCard() }
                        .secondaryAction()
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

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            SystemPlayer(controller: controller)
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
    }
}

struct SystemPlayer: UIViewControllerRepresentable {
    @ObservedObject var controller: PlaybackController

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
            actions.append(UIAction(title: "Next Episode", image: UIImage(systemName: "forward.frame")) { [weak controller] _ in controller?.playNext() })
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
        }
        let subtitles = controller.subtitleStreams
        if !subtitles.isEmpty {
            let off = UIAction(title: "Off", state: controller.selectedSubtitle < 0 ? .on : .off) { [weak controller] _ in controller?.selectSubtitle(-1) }
            menus.append(UIMenu(title: "Subtitles", image: UIImage(systemName: "captions.bubble"), options: .singleSelection, children: [off] + subtitles.map { stream in
                UIAction(title: controller.label(stream), state: stream.index == controller.selectedSubtitle ? .on : .off) { [weak controller] _ in controller?.selectSubtitle(stream.index) }
            }))
        }
        menus.append(UIAction(title: controller.methodDescription, image: UIImage(systemName: "info.circle"), attributes: .disabled) { _ in })
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

    var body: some View {
        ZStack {
            VideoSurface(player: controller.player)
                .ignoresSafeArea()
                .onTapGesture { toggleControls() }
                .accessibilityHidden(true)
            PlayerOverlays(controller: controller, controlsVisible: controlsVisible)
            if controlsVisible { controls.transition(.opacity) }
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
        .background(Color.black)
        .animation(.easeInOut(duration: 0.2), value: controlsVisible)
        .onAppear { scheduleHide() }
        .onChange(of: controller.isPlaying) { scheduleHide() }
    }

    private var controls: some View {
        ZStack {
            LinearGradient(colors: [.black.opacity(0.7), .clear, .clear, .black.opacity(0.8)], startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea()
                .allowsHitTesting(false)
            VStack(spacing: 0) {
                topBar
                Spacer()
                centerButtons
                Spacer()
                if let notice = controller.notice, !controller.noticeDismissed {
                    ConversionNoticeCard(notice: notice) { controller.noticeDismissed = true }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.bottom, 12)
                }
                scrubber
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
            Spacer()
            trackMenu
            RoutePicker().frame(width: 44, height: 44).accessibilityLabel("AirPlay")
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
            }
            if !controller.subtitleStreams.isEmpty {
                Picker("Subtitles", selection: Binding(get: { controller.selectedSubtitle }, set: { controller.selectSubtitle($0) })) {
                    Text("Off").tag(-1)
                    ForEach(controller.subtitleStreams) { Text(controller.label($0)).tag($0.index) }
                }
                .pickerStyle(.menu)
            }
            Section(controller.methodDescription) {}
        } label: {
            Image(systemName: "captions.bubble").font(.title3).frame(width: 44, height: 44)
        }
        .accessibilityLabel("Audio and subtitles")
        .simultaneousGesture(TapGesture().onEnded { scheduleHide() })
    }

    private var centerButtons: some View {
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
        guard controlsVisible, controller.isPlaying else { return }
        hideTask = Task {
            try? await Task.sleep(for: .seconds(4))
            if !Task.isCancelled, scrubbing == nil { controlsVisible = false }
        }
    }
}

/// AVPlayerLayer-backed view.
struct VideoSurface: UIViewRepresentable {
    let player: AVPlayer
    func makeUIView(context: Context) -> PlayerLayerView {
        let view = PlayerLayerView()
        view.playerLayer.player = player
        view.playerLayer.videoGravity = .resizeAspect
        view.backgroundColor = .black
        return view
    }
    func updateUIView(_ view: PlayerLayerView, context: Context) {}

    final class PlayerLayerView: UIView {
        override class var layerClass: AnyClass { AVPlayerLayer.self }
        var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    }
}

struct RoutePicker: UIViewRepresentable {
    func makeUIView(context: Context) -> AVRoutePickerView {
        let view = AVRoutePickerView()
        view.tintColor = .white
        view.prioritizesVideoDevices = true
        return view
    }
    func updateUIView(_ view: AVRoutePickerView, context: Context) {}
}
#endif
