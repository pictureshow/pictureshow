// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import SwiftUI
import PictureshowCore

struct SettingsView: View {
    @EnvironmentObject private var app: AppModel

    var body: some View {
        Form {
            accounts
            playback
            Section("Skip segments") {
                ForEach([SegmentType.intro, .outro, .recap, .preview, .commercial], id: \.self) { type in
                    Picker(segmentTitle(type), selection: segmentBinding(type)) {
                        Text("Show a Skip button").tag(SegmentMode.ask)
                        Text("Skip automatically").tag(SegmentMode.automatic)
                        Text("Don't skip").tag(SegmentMode.off)
                    }
                }
            }
            LanguageSection()
            Section("Subtitles") {
                Picker("Size", selection: $app.preferences.playback.subtitleSize) {
                    ForEach(SubtitleSize.allCases, id: \.self) { Text(sizeTitle($0)).tag($0) }
                }
                Picker("Background", selection: $app.preferences.playback.subtitleBackground) {
                    ForEach(SubtitleBackground.allCases, id: \.self) { Text(backgroundTitle($0)).tag($0) }
                }
            }
            #if os(iOS)
            Section("Appearance") {
                Picker("Theme", selection: $app.preferences.appearance) {
                    ForEach(Appearance.allCases) { Text($0.title).tag($0) }
                }
            }
            #endif
            Section("About") {
                LabeledContent("Version", value: "\(AppInfo.version) (\(AppInfo.build))")
                NavigationLink("License") { LicenseView() }
                Text("Pictureshow is free and open source, made by Legitimate LLC.")
                    .font(.footnote).foregroundStyle(Theme.muted)
            }
        }
        .navigationTitle("Settings")
        #if os(iOS)
        .scrollContentBackground(.hidden)
        .pageBackground()
        #endif
    }

    private var accounts: some View {
        Section {
            ForEach(app.accounts, id: \.key) { account in
                Button { app.select(account) } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(account.username).font(.body.weight(.semibold))
                            Text(account.server.name).font(.caption).foregroundStyle(Theme.muted)
                        }
                        Spacer()
                        if account.key == app.account?.key { Image(systemName: "checkmark").foregroundStyle(Theme.accent) }
                    }
                    .contentShape(Rectangle())
                }
                .accessibilityAddTraits(account.key == app.account?.key ? .isSelected : [])
                #if os(iOS)
                .buttonStyle(.plain)
                .swipeActions { Button("Sign out", role: .destructive) { app.signOut(account) } }
                #endif
                .contextMenu { Button("Sign out", role: .destructive) { app.signOut(account) } }
            }
            Button { app.addingAccount = true } label: { Label("Add account", systemImage: "person.badge.plus") }
            if let account = app.account {
                Button(role: .destructive) { app.signOut(account) } label: { Label("Sign out of \(account.username)", systemImage: "rectangle.portrait.and.arrow.right") }
            }
        } header: {
            Text("Accounts")
        } footer: {
            Text("Each account keeps its own watch history. Switching doesn't sign anyone out.")
        }
    }

    private var playback: some View {
        Section {
            Picker("Streaming quality", selection: $app.preferences.playback.bitrateMbps) {
                ForEach(PlaybackPreferences.bitrates, id: \.self) { value in
                    Text(value.map { "Up to \($0) Mbps" } ?? "Best available").tag(value)
                }
            }
            Toggle("Play the next episode automatically", isOn: $app.preferences.autoplayNextEpisode)
            Toggle("Explain when the server converts video", isOn: $app.preferences.playback.showConversionNotice)
        } header: {
            Text("Playback")
        } footer: {
            Text("Lower quality only applies when the server has to convert a file. Files this device plays as they are always play at full quality.")
        }
    }

    private func segmentBinding(_ type: SegmentType) -> Binding<SegmentMode> {
        Binding(get: { app.preferences.playback.segmentModes[type] ?? .ask },
                set: { app.preferences.playback.segmentModes[type] = $0 })
    }

    private func segmentTitle(_ type: SegmentType) -> String {
        switch type {
        case .intro: return "Intros"
        case .outro: return "Credits"
        case .recap: return "Recaps"
        case .preview: return "Previews"
        case .commercial: return "Commercials"
        case .unknown: return "Other"
        }
    }

    private func sizeTitle(_ size: SubtitleSize) -> String {
        switch size { case .system: return "System"; case .small: return "Small"; case .normal: return "Normal"; case .large: return "Large"; case .extraLarge: return "Extra large" }
    }

    private func backgroundTitle(_ background: SubtitleBackground) -> String {
        switch background { case .system: return "System"; case .none: return "None"; case .shadow: return "Shadow"; case .box: return "Box" }
    }
}

/// Audio and subtitle language preferences live on the server, so every Jellyfin app the viewer uses agrees.
struct LanguageSection: View {
    @EnvironmentObject private var app: AppModel
    @State private var configuration: UserConfiguration?
    @State private var error: String?

    private static let languages: [(code: String, name: String)] = {
        let codes = ["eng", "spa", "fre", "ger", "ita", "por", "jpn", "kor", "chi", "rus", "ara", "hin", "dut", "swe", "nor", "dan", "fin", "pol", "tur", "heb", "tha", "vie", "ind", "ukr", "ces", "gre", "hun", "ron"]
        return codes.map { code in (code, Locale.current.localizedString(forLanguageCode: code) ?? code) }.sorted { $0.name < $1.name }
    }()

    var body: some View {
        Section {
            if let configuration {
                Picker("Audio language", selection: binding("AudioLanguagePreference", configuration.audioLanguage)) {
                    Text("File default").tag("")
                    ForEach(Self.languages, id: \.code) { Text($0.name).tag($0.code) }
                }
                Picker("Subtitle language", selection: binding("SubtitleLanguagePreference", configuration.subtitleLanguage)) {
                    Text("Any").tag("")
                    ForEach(Self.languages, id: \.code) { Text($0.name).tag($0.code) }
                }
                Picker("Subtitles", selection: binding("SubtitleMode", configuration.subtitleMode)) {
                    Text("Default").tag("Default")
                    Text("Always on").tag("Always")
                    Text("Only forced").tag("OnlyForced")
                    Text("When audio is another language").tag("Smart")
                    Text("Off").tag("None")
                }
            } else if let error {
                Text(error).foregroundStyle(Theme.warning)
            } else {
                ProgressView()
            }
        } header: {
            Text("Languages")
        } footer: {
            Text("Saved to your Jellyfin account, so other Jellyfin apps use them too.")
        }
        .task(id: app.account?.key) {
            guard let account = app.account, let client = app.client else { return }
            do { configuration = try await client.configuration(account: account) } catch { self.error = error.localizedDescription }
        }
    }

    private func binding(_ field: String, _ value: String) -> Binding<String> {
        Binding(get: { value }, set: { newValue in
            guard let current = configuration, let updated = try? current.withPreference(field, value: newValue) else { return }
            configuration = updated
            Task {
                guard let account = app.account, let client = app.client else { return }
                do { try await client.saveConfiguration(account: account, configuration: updated) }
                catch { self.error = error.localizedDescription; configuration = current }
            }
        })
    }
}

struct LicenseView: View {
    private var text: String {
        Bundle.main.url(forResource: "MPL-2.0", withExtension: "txt").flatMap { try? String(contentsOf: $0, encoding: .utf8) } ?? "Mozilla Public License 2.0"
    }
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("Pictureshow is licensed under the Mozilla Public License 2.0. Copyright Legitimate LLC.").font(.headline)
                Text(text).font(.system(.footnote, design: .monospaced))
            }
            .padding(Theme.gutter)
            #if os(tvOS)
            .focusable()
            #endif
        }
        .navigationTitle("License")
        .pageBackground()
    }
}
