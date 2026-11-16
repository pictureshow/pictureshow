// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import SwiftUI
import PictureshowCore

/// PHOTO-01/02: a photo full screen among the photos of its folder, with a slideshow.
struct PhotoViewer: View {
    @EnvironmentObject private var app: AppModel
    @ObservedObject private var settings = ViewingSettings.shared
    @Environment(\.dismiss) private var dismiss
    let photo: MediaItem
    let sortBy: String
    let descending: Bool

    @State private var photos: [MediaItem] = []
    @State private var index = 0
    @State private var controlsShown = true
    @State private var slideshow = false
    @State private var fadeTask: Task<Void, Never>?
    @State private var slideTask: Task<Void, Never>?
    #if os(tvOS)
    @FocusState private var photoFocused: Bool
    #endif

    private var current: MediaItem? { photos.indices.contains(index) ? photos[index] : nil }

    var body: some View {
        GeometryReader { geo in
            ZStack {
                Color.black.ignoresSafeArea()
                pages(size: geo.size)
                if controlsShown, let current { controls(current) }
            }
        }
        .ignoresSafeArea()
        .background(Color.black)
        #if os(iOS)
        .toolbar(controlsShown ? .visible : .hidden, for: .navigationBar)
        .toolbar(.hidden, for: .tabBar)
        .statusBarHidden(!controlsShown)
        .navigationBarTitleDisplayMode(.inline)
        #else
        .toolbar(.hidden, for: .navigationBar)
        .onPlayPauseCommand { toggleSlideshow() }
        .onExitCommand {
            if controlsShown { controlsShown = false; photoFocused = true } else { dismiss() }
        }
        #endif
        .task { await load() }
        .onAppear { ScreensaverGate.shared.blockers += 1; showControls() }
        .onDisappear {
            ScreensaverGate.shared.blockers -= 1
            fadeTask?.cancel(); slideTask?.cancel()
            UIApplication.shared.isIdleTimerDisabled = false
        }
        .onChange(of: slideshow) { _, running in
            // PHOTO-02: the screen stays on only while the slideshow runs.
            UIApplication.shared.isIdleTimerDisabled = running
            scheduleSlide()
        }
        .onChange(of: settings.slideshowSeconds) { scheduleSlide() }
        .onChange(of: index) { scheduleSlide() }
    }

    // MARK: Pages

    @ViewBuilder private func pages(size: CGSize) -> some View {
        #if os(iOS)
        TabView(selection: $index) {
            ForEach(Array(photos.enumerated()), id: \.element.id) { offset, item in
                PhotoImage(item: item, size: size).tag(offset)
            }
        }
        .tabViewStyle(.page(indexDisplayMode: .never))
        .contentShape(Rectangle())
        .onTapGesture { controlsShown ? hideControls() : showControls() }
        .accessibilityLabel(current?.name ?? photo.name)
        #else
        Button { showControls() } label: {
            ZStack { if let current { PhotoImage(item: current, size: size).id(current.id).transition(.opacity) } }
                .frame(width: size.width, height: size.height)
        }
        .buttonStyle(BareButtonStyle())
        .focused($photoFocused)
        .onMoveCommand { direction in
            switch direction {
            case .left: step(forward: false)
            case .right: step(forward: true)
            default: showControls()
            }
        }
        .defaultFocus($photoFocused, true)
        .accessibilityLabel(current?.name ?? photo.name)
        #endif
    }

    // MARK: Controls

    private func controls(_ item: MediaItem) -> some View {
        VStack {
            HStack(alignment: .firstTextBaseline) {
                Text(item.name).font(platformValue(tv: .title3.weight(.semibold), touch: .headline)).lineLimit(1)
                Spacer()
                Text("\(index + 1) of \(photos.count)").font(platformValue(tv: .callout, touch: .subheadline)).monospacedDigit()
                    .accessibilityIdentifier("photo-position")
            }
            .foregroundStyle(.white)
            .padding(.horizontal, Theme.gutter)
            .padding(.top, platformValue(tv: 50, touch: 100))
            Spacer()
            HStack(spacing: 20) {
                Button { toggleSlideshow() } label: {
                    Label(slideshow ? "Stop slideshow" : "Start slideshow", systemImage: slideshow ? "pause.fill" : "play.fill")
                }
                .primaryAction()
                .disabled(photos.count < 2)
                Menu {
                    Picker("Speed", selection: $settings.slideshowSeconds) {
                        ForEach(Discovery.slideshowSeconds, id: \.self) { Text("\($0) seconds").tag($0) }
                    }
                } label: { Label("Every \(settings.slideshowSeconds) s", systemImage: "timer") }
                .secondaryAction()
            }
            .padding(.bottom, platformValue(tv: 60, touch: 40))
            #if os(tvOS)
            .focusSection()
            #endif
        }
        .background(alignment: .top) { LinearGradient(colors: [.black.opacity(0.7), .clear], startPoint: .top, endPoint: .bottom).frame(height: 220).allowsHitTesting(false) }
        .background(alignment: .bottom) { LinearGradient(colors: [.clear, .black.opacity(0.7)], startPoint: .top, endPoint: .bottom).frame(height: 220).allowsHitTesting(false) }
        .ignoresSafeArea()
        .transition(.opacity)
    }

    // MARK: Behaviour

    private func load() async {
        guard photos.isEmpty else { return }
        photos = [photo]
        guard let account = app.account, let client = app.client else { return }
        var parent = photo.parentId
        if parent == nil { parent = (try? await client.item(account: account, id: photo.id))?.parentId }
        guard let parent else { return }
        // PHOTO-01: a failed folder read still shows the chosen photo.
        guard let list = try? await client.photos(account: account, parentID: parent, sortBy: sortBy, descending: descending),
              let position = list.firstIndex(where: { $0.id == photo.id }) else { return }
        photos = list
        index = position
    }

    private func step(forward: Bool) {
        guard let next = Discovery.photoStep(index: index, count: photos.count, forward: forward, slideshow: false) else { return }
        withAnimation(.easeInOut(duration: 0.3)) { index = next }
    }

    private func toggleSlideshow() {
        guard photos.count > 1 else { return }
        slideshow.toggle()
        if slideshow { hideControls() } else { showControls() }
    }

    private func scheduleSlide() {
        slideTask?.cancel()
        guard slideshow else { return }
        let seconds = settings.slideshowSeconds
        slideTask = Task { @MainActor in
            try? await Task.sleep(for: .seconds(seconds))
            guard !Task.isCancelled, slideshow else { return }
            if let next = Discovery.photoStep(index: index, count: photos.count, forward: true, slideshow: true) {
                withAnimation(.easeInOut(duration: 0.8)) { index = next }
            }
        }
    }

    private func showControls() {
        withAnimation(.easeOut(duration: 0.2)) { controlsShown = true }
        fadeTask?.cancel()
        // PHOTO-01: the controls fade after five seconds.
        fadeTask = Task { @MainActor in
            try? await Task.sleep(for: .seconds(5))
            guard !Task.isCancelled else { return }
            hideControls()
        }
    }

    private func hideControls() {
        fadeTask?.cancel()
        withAnimation(.easeIn(duration: 0.3)) { controlsShown = false }
        #if os(tvOS)
        photoFocused = true
        #endif
    }
}

/// One photo, asked at the screen's size and fitted inside it.
private struct PhotoImage: View {
    @EnvironmentObject private var app: AppModel
    let item: MediaItem
    let size: CGSize
    @State private var image: UIImage?
    @State private var failed = false
    @Environment(\.displayScale) private var scale

    var body: some View {
        ZStack {
            if let image {
                Image(uiImage: image).resizable().aspectRatio(contentMode: .fit)
            } else if failed {
                Image(systemName: "photo").font(.system(size: 48, weight: .light)).foregroundStyle(.white.opacity(0.5))
            } else {
                ProgressView().tint(.white)
            }
        }
        .frame(width: size.width, height: size.height)
        .task(id: item.id) { await load() }
    }

    private func load() async {
        guard let account = app.account, let client = app.client,
              let url = Discovery.photoImageURL(server: account.server.url, item: item, maxWidth: Int(size.width * scale), maxHeight: Int(size.height * scale)) else { failed = true; return }
        let result = await ArtworkLoader.shared.image(url, account: account, client: client)
        guard !Task.isCancelled else { return }
        image = result
        failed = result == nil
    }
}

/// A button with no focus decoration, for a full-screen surface that only needs the remote's presses.
private struct BareButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View { configuration.label }
}
