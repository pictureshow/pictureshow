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
        }
    }
}

struct RootView: View {
    @EnvironmentObject private var app: AppModel

    var body: some View {
        Group {
            if let account = app.account {
                MainShell().id(account.key)
            } else {
                NavigationStack { SignInFlowView() }
            }
        }
        .pageBackground()
        .alert("Sign-in not saved", isPresented: Binding(get: { app.storageError != nil }, set: { if !$0 { app.storageError = nil } })) {
            Button("Try again") { app.persist() }
            Button("OK", role: .cancel) { app.storageError = nil }
        } message: {
            Text(app.storageError ?? "")
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

enum MainTab: Hashable { case home, libraries, search, settings }

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
            Tab("Settings", systemImage: "gearshape", value: MainTab.settings) {
                RoutedStack { SettingsView() }
            }
        }
        #if os(iOS)
        .tabViewStyle(.sidebarAdaptable)
        #endif
    }
}

/// A navigation stack that knows how to open every route the catalog produces.
struct RoutedStack<Root: View>: View {
    @ViewBuilder var root: () -> Root
    var body: some View {
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
