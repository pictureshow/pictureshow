// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import SwiftUI
import AVFoundation
import PictureshowCore

@main struct PictureshowApp: App {
    @StateObject private var app = AppModel()

    init() {
        #if os(iOS)
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        #endif
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(app)
                .tint(Theme.accent)
                .preferredColorScheme(app.preferences.appearance.scheme)
                #if DEBUG && os(iOS)
                .task { await DebugOrientation.applyFromEnvironment() }
                #endif
                #if DEBUG
                .task(id: app.account?.key) { await DebugLaunch.playFromEnvironment(app) }
                #endif
        }
        #if os(iOS)
        // A transfer that finished while the app was gone wakes it; the engine's sessions take the events.
        .backgroundTask(.urlSession("app.pictureshow.downloads.files")) {}
        .backgroundTask(.urlSession("app.pictureshow.downloads.hls")) {}
        #endif
    }
}

struct RootView: View {
    @EnvironmentObject private var app: AppModel
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        Group {
            if let account = app.account {
                MainShell().id(account.key)
            } else {
                NavigationStack { SignInFlowView() }
            }
        }
        .pageBackground()
        // SYN-08: video rendering in the background leaves the group once; with no player open the device stays.
        .onChange(of: scenePhase) { _, phase in
            guard phase == .background, let controller = app.activeController, let box = controller.syncRenderer,
                  controller.grouped, controller.isUnpaused else { return }
            controller.player.pause()
            app.syncPlay.engine.closeViewing(box, reason: .BACKGROUND_RENDERING)
            app.syncPlay.changed()
        }
        .alert("Sign-in not saved", isPresented: Binding(get: { app.storageError != nil }, set: { if !$0 { app.storageError = nil } })) {
            Button("Try again") { app.persist() }
            Button("OK", role: .cancel) { app.storageError = nil }
        } message: {
            Text(app.storageError ?? "")
        }
        .sheet(item: $app.signingInAgain) { account in
            NavigationStack {
                PasswordView(server: account.server, username: account.username, deviceID: account.deviceID)
                    .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { app.signingInAgain = nil } } }
            }
            .environmentObject(app)
        }
        .sheet(isPresented: $app.addingAccount) {
            NavigationStack { SignInFlowView(isAddingAccount: true) }
                .environmentObject(app)
        }
        #if os(tvOS)
        .fullScreenCover(item: $app.player) { request in
            PlayerScreen(request: request).environmentObject(app)
        }
        #else
        .fullScreenCover(item: $app.player) { request in
            PlayerScreen(request: request).environmentObject(app)
        }
        #endif
    }
}

enum MainTab: Hashable { case home, libraries, search, downloads, settings }

/// Tab bar on iPhone and Apple TV; on iPad the same tabs adapt into a sidebar.
struct MainShell: View {
    @EnvironmentObject private var app: AppModel
    @State private var tab: MainTab = .home

    var body: some View {
        TabView(selection: $tab) {
            Tab("Home", systemImage: "house", value: MainTab.home) {
                RoutedStack { HomeView() }
            }
            Tab("Libraries", systemImage: "rectangle.stack", value: MainTab.libraries) {
                RoutedStack { LibrariesView() }
            }
            Tab(value: MainTab.search, role: .search) {
                RoutedStack { SearchView() }
            }
            #if os(iOS)
            Tab("Downloads", systemImage: "arrow.down.circle", value: MainTab.downloads) {
                RoutedStack { DownloadsView() }
            }
            #endif
            Tab("Settings", systemImage: "gearshape", value: MainTab.settings) {
                RoutedStack { SettingsView() }
            }
        }
        #if os(iOS)
        .tabViewStyle(.sidebarAdaptable)
        .modifier(RemoteSheets(remote: app.remote))
        // With no network at all, a cold start goes straight to what still plays (OFF-04).
        .task {
            try? await Task.sleep(for: .milliseconds(400))
            if !app.downloads.networkUp, app.downloads.hasReady(app.account) { tab = .downloads }
        }
        .onChange(of: app.showingDownloads) { _, show in if show { tab = .downloads; app.showingDownloads = false } }
        #endif
    }
}

/// A navigation stack that knows how to open every route the catalog produces.
struct RoutedStack<Root: View>: View {
    @EnvironmentObject private var app: AppModel
    @ViewBuilder var root: () -> Root
    var body: some View {
        stack
        #if os(iOS)
            .safeAreaInset(edge: .bottom, spacing: 0) { if !RemoteBar.usesAccessory { RemoteBar(remote: app.remote) } }
        #endif
    }

    private var stack: some View {
        NavigationStack {
            root()
                .navigationDestination(for: Route.self) { route in
                    switch route {
                    case .item(let item): DetailView(seed: item)
                    case .library(let library): LibraryView(library: library)
                    case .person(let id, let name): PersonView(personID: id, name: name)
                    }
                }
        }
    }
}

#if DEBUG && os(iOS)
/// Test seam for headless simulators, which have no rotate menu: launching with PICTURESHOW_ORIENTATION=landscape
/// (simctl: SIMCTL_CHILD_PICTURESHOW_ORIENTATION) turns the window. Debug builds only.
enum DebugOrientation {
    @MainActor static func applyFromEnvironment() async {
        guard let wanted = ProcessInfo.processInfo.environment["PICTURESHOW_ORIENTATION"] else { return }
        let mask: UIInterfaceOrientationMask = wanted == "landscape" ? .landscapeRight : .portrait
        // The request is honoured only once the scene is in the foreground.
        for _ in 0..<20 {
            if let scene = UIApplication.shared.connectedScenes.first(where: { $0.activationState == .foregroundActive }) as? UIWindowScene {
                scene.requestGeometryUpdate(.iOS(interfaceOrientations: mask)) { error in NSLog("PICTURESHOW_ORIENTATION refused: %@", error.localizedDescription) }
                scene.keyWindow?.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
                return
            }
            try? await Task.sleep(for: .milliseconds(250))
        }
    }
}
#endif

#if DEBUG
/// Test seam: launching with PICTURESHOW_PLAY=<item id> (simctl: SIMCTL_CHILD_PICTURESHOW_PLAY) plays that item for
/// the signed-in viewer, so a headless Apple TV can reach a given file without typing a search. Debug builds only.
enum DebugLaunch {
    private static var done = false
    @MainActor static func playFromEnvironment(_ app: AppModel) async {
        guard !done, let id = ProcessInfo.processInfo.environment["PICTURESHOW_PLAY"],
              let account = app.account, let client = app.client,
              let item = try? await client.item(account: account, id: id) else { return }
        done = true
        app.play(item, fromStart: ProcessInfo.processInfo.environment["PICTURESHOW_FROM_START"] == "1")
    }
}
#endif

#if os(iOS)
/// The Play on chooser and the remote, presented over every tab.
struct RemoteSheets: ViewModifier {
    @EnvironmentObject private var app: AppModel
    @ObservedObject var remote: RemoteControl
    func body(content: Content) -> some View {
        accessory(content)
            .sheet(isPresented: $remote.choosing) { PlayOnSheet(remote: remote).environmentObject(app) }
            .sheet(isPresented: $remote.showingRemote) { RemoteScreen(remote: remote).presentationDetents([.large]) }
    }

    @ViewBuilder private func accessory(_ content: Content) -> some View {
        if #available(iOS 26.1, *) {
            content.tabViewBottomAccessory(isEnabled: remote.link != .none) { RemoteBar(remote: remote, accessory: true) }
        } else {
            content
        }
    }
}
#endif
