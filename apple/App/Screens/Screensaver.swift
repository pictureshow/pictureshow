// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import SwiftUI
import PictureshowCore

/// Screens that keep the screensaver away while they are open (the photo viewer; the player is checked directly).
@MainActor final class ScreensaverGate {
    static let shared = ScreensaverGate()
    var blockers = 0
}

/// SAVER-01 on Apple TV. tvOS lets no app provide the system screensaver, so this is an in-app one: after the chosen
/// time without a press, outside the player and the photo viewer, the app covers itself with library backdrops, the
/// title, the time and the date. The system's own screensaver still starts after its own idle time in Settings.
struct ScreensaverHost: ViewModifier {
    @EnvironmentObject private var app: AppModel
    @ObservedObject private var settings = ViewingSettings.shared
    @State private var showing = false

    func body(content: Content) -> some View {
        #if os(tvOS)
        content
            .fullScreenCover(isPresented: $showing) { ScreensaverView { showing = false }.environmentObject(app) }
            .task(id: settings.screensaverMinutes) { await watch() }
        #else
        content
        #endif
    }

    private func watch() async {
        let minutes = settings.screensaverMinutes
        guard minutes > 0 else { return }
        while !Task.isCancelled {
            let idle = Date().timeIntervalSince(ViewerInput.shared.lastInput)
            let due = Double(minutes * 60)
            if idle >= due, !showing, Self.allowed(app: app) { showing = true }
            try? await Task.sleep(for: .seconds(idle >= due ? 5 : min(30, max(1, due - idle))))
        }
    }

    static func allowed(app: AppModel) -> Bool {
        app.player == nil && app.account != nil && ScreensaverGate.shared.blockers == 0
    }
}

#if os(tvOS)
private struct ScreensaverView: View {
    @EnvironmentObject private var app: AppModel
    @ObservedObject private var settings = ViewingSettings.shared
    let wake: () -> Void
    @State private var art: [MediaItem] = []
    @State private var index = 0
    @FocusState private var focused: Bool

    var body: some View {
        // One full-screen button takes every press, so the key that wakes the app does nothing else.
        Button(action: wake) {
            ZStack {
                Color.black
                if art.indices.contains(index) { backdrop(art[index]).id(art[index].id).transition(.opacity) }
                VStack {
                    Spacer()
                    HStack(alignment: .bottom) {
                        if art.indices.contains(index) {
                            Text(art[index].name).font(.title3).foregroundStyle(Color(white: 0.9))
                        }
                        Spacer()
                        TimelineView(.everyMinute) { context in
                            VStack(alignment: .trailing, spacing: 4) {
                                Text(context.date, style: .time).font(.system(size: 64, weight: .semibold)).foregroundStyle(.white)
                                Text(context.date.formatted(.dateTime.weekday(.wide).month(.wide).day())).font(.title3).foregroundStyle(Color(white: 0.87))
                            }
                        }
                    }
                    .padding(.horizontal, 90).padding(.bottom, 60)
                }
            }
            .ignoresSafeArea()
        }
        .buttonStyle(SaverButtonStyle())
        .focused($focused)
        .onAppear { focused = true }
        .onMoveCommand { _ in wake() }
        .onPlayPauseCommand { wake() }
        .onExitCommand { wake() }
        .accessibilityHidden(true)
        .ignoresSafeArea()
        .task { await load() }
        .task(id: art.count) { await cycle() }
    }

    private func backdrop(_ item: MediaItem) -> some View {
        ArtworkImage(url: app.account.flatMap { a in app.client?.artworkURL(account: a, item: item, kind: .backdrop, width: 1920) })
            .overlay { LinearGradient(stops: [.init(color: .clear, location: 0.55), .init(color: .black.opacity(0.8), location: 1)], startPoint: .top, endPoint: .bottom) }
    }

    private func load() async {
        guard let account = app.account, let client = app.client else { return }
        let items = (try? await client.screensaverItems(account: account)) ?? []
        var ages: [String: Int] = [:]
        if settings.screensaverMaxAge != nil {
            for rating in (try? await client.parentalRatings(account: account)) ?? [] { if let value = rating.value { ages[rating.name] = value } }
        }
        art = Discovery.screensaverArt(items, maxAge: settings.screensaverMaxAge) { ages[$0] }
    }

    private func cycle() async {
        while art.count > 1, !Task.isCancelled {
            try? await Task.sleep(for: .seconds(12))
            guard !Task.isCancelled else { return }
            withAnimation(.easeInOut(duration: 2)) { index = (index + 1) % art.count }
        }
    }
}

private struct SaverButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View { configuration.label }
}
#endif
