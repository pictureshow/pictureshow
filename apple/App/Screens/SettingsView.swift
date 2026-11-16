// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import SwiftUI
import PictureshowCore

struct SettingsView: View {
    @EnvironmentObject private var app: AppModel
    @State private var signOutAccount: Account?

    var body: some View {
        Form {
            accounts
            Section {
                Toggle("Separate Next up row", isOn: Binding(
                    get: { !app.homeLayout.mergeWatching },
                    set: { separate in Task { await app.setSeparateNextUp(separate) } }
                ))
                .disabled(app.homeLayoutSaving)
                if let error = app.homeLayoutError {
                    Text("Couldn't save or load Home settings: \(error)").font(.footnote).foregroundStyle(Theme.warning)
                    Button("Retry") { Task { await app.retryHomeLayout() } }
                }
            } header: { Text("Home") } footer: {
                Text(app.homeLayout.mergeWatching ? "Next up appears in Continue watching, captioned Next." : "Continue watching and Next up appear in separate rows.")
            }
            .task(id: app.account?.key) {
                await app.retryHomeLayout()
            }
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
            Section {
                NavigationLink("About Pictureshow") { AboutView() }
                NavigationLink(DeviceIdentity.thisDevice) { DeviceInfoView() }
                NavigationLink("Support Pictureshow") { SupportView() }
                #if os(iOS)
                NavigationLink("Downloads") { DownloadsView() }
                #else
                NavigationLink("Downloads") { DownloadsUnsupportedView() }
                #endif
            } header: {
                Text("Pictureshow")
            } footer: {
                Text("\(DeviceIdentity.thisDevice) shows what plays directly here and what the server converts.")
            }
        }
        .confirmSignOut(account: $signOutAccount)
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
                            Text(account.isSignedIn ? account.server.name : "\(account.server.name) · Signed out · Sign in again")
                                .font(.caption).foregroundStyle(account.isSignedIn ? Theme.muted : Theme.accent)
                        }
                        Spacer()
                        if account.key == app.account?.key { Image(systemName: "checkmark").foregroundStyle(Theme.accent) }
                        else if !account.isSignedIn { Image(systemName: "chevron.right").foregroundStyle(Theme.muted) }
                    }
                    .contentShape(Rectangle())
                }
                .accessibilityAddTraits(account.key == app.account?.key ? .isSelected : [])
                .accessibilityHint(account.isSignedIn ? "" : "Signed out. Opens sign-in.")
                #if os(iOS)
                .buttonStyle(.plain)
                .swipeActions {
                    Button("Remove", role: .destructive) { app.remove(account) }
                    if account.isSignedIn { Button("Sign out") { signOutAccount = account }.tint(.orange) }
                }
                #endif
                .contextMenu {
                    if account.isSignedIn { Button("Sign out", role: .destructive) { signOutAccount = account } }
                    Button("Remove from this device", role: .destructive) { app.remove(account) }
                }
            }
            Button { app.addingAccount = true } label: { Label("Add account", systemImage: "person.badge.plus") }
            if let account = app.account {
                Button(role: .destructive) { signOutAccount = account } label: { Label("Sign out of \(account.username)", systemImage: "rectangle.portrait.and.arrow.right") }
            }
        } header: {
            Text("Accounts")
        } footer: {
            Text("Each account keeps its own watch history. Switching doesn't sign anyone out, and a signed-out account stays here until you remove it.")
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
            // Apple devices offer no app-level passthrough switch, so this is a read-only line rather than
            // a toggle that would do nothing (SET-02).
            VStack(alignment: .leading, spacing: 4) {
                LabeledContent("Audio passthrough", value: "Set by \(DeviceIdentity.systemName)")
                Text(Self.passthroughCaption).font(.footnote).foregroundStyle(Theme.muted)
            }
            .accessibilityElement(children: .combine)
            VStack(alignment: .leading, spacing: 4) {
                Toggle("Conversion notice", isOn: $app.preferences.playback.showConversionNotice)
                Text("Shows a short note when the server has to convert a video for this \(DeviceIdentity.name).")
                    .font(.footnote).foregroundStyle(Theme.muted)
            }
        } header: {
            Text("Playback")
        } footer: {
            Text("Lower quality only applies when the server has to convert a file. Files this device plays as they are always play at full quality.")
        }
    }

    @MainActor private static var passthroughCaption: String {
        #if os(tvOS)
        "Apple TV sends Dolby Digital and Dolby Atmos to your receiver when it can. Change it in Settings > Video and Audio."
        #else
        "\(DeviceIdentity.systemName) decides how sound reaches your speakers, headphones or AirPlay receiver."
        #endif
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
    @State private var loadError: String?
    @State private var saveError: String?
    @State private var saving = false
    @State private var attempt = 0

    private static let languages: [(code: String, name: String)] = {
        let codes = ["eng", "spa", "fre", "ger", "ita", "por", "jpn", "kor", "chi", "rus", "ara", "hin", "dut", "swe", "nor", "dan", "fin", "pol", "tur", "heb", "tha", "vie", "ind", "ukr", "ces", "gre", "hun", "ron"]
        return codes.map { code in (code, Locale.current.localizedString(forLanguageCode: code) ?? code) }.sorted { $0.name < $1.name }
    }()

    var body: some View {
        Section {
            if let configuration {
                Picker("Audio language", selection: binding("AudioLanguagePreference", configuration.audioLanguage)) {
                    Text("No preference").tag("")
                    ForEach(Self.languages, id: \.code) { Text($0.name).tag($0.code) }
                }
                Picker("Subtitle language", selection: binding("SubtitleLanguagePreference", configuration.subtitleLanguage)) {
                    Text("No preference").tag("")
                    ForEach(Self.languages, id: \.code) { Text($0.name).tag($0.code) }
                }
                Picker("Subtitles", selection: binding("SubtitleMode", configuration.subtitleMode)) {
                    Text("Default").tag("Default")
                    Text("Always on").tag("Always")
                    Text("Only forced").tag("OnlyForced")
                    Text("When audio is another language").tag("Smart")
                    Text("Off").tag("None")
                }
                if let saveError {
                    Text("Couldn't save: \(saveError). Your previous choice is kept.").font(.footnote).foregroundStyle(Theme.warning)
                }
            } else if let loadError {
                Text("Couldn't load your language settings: \(loadError)").foregroundStyle(Theme.warning)
                Button("Retry") { self.loadError = nil; attempt += 1 }
            } else {
                LabeledContent("Languages", value: "Loading…")
            }
        } header: {
            Text("Languages")
        } footer: {
            Text("Saved to your Jellyfin account, so other Jellyfin apps use them too.")
        }
        .disabled(saving)
        .task(id: "\(app.account?.key ?? "")#\(attempt)") {
            guard let account = app.account, let client = app.client else { return }
            configuration = nil
            do { configuration = try await client.configuration(account: account) }
            catch ClientError.invalidCredentials { app.expire(account) }
            catch is CancellationError {}
            catch { loadError = error.localizedDescription }
        }
    }

    private func binding(_ field: String, _ value: String) -> Binding<String> {
        Binding(get: { value }, set: { newValue in
            guard let current = configuration, let updated = try? current.withPreference(field, value: newValue) else { return }
            configuration = updated
            saveError = nil
            saving = true
            Task {
                defer { saving = false }
                guard let account = app.account, let client = app.client else { return }
                do { try await client.saveConfiguration(account: account, configuration: updated) }
                catch ClientError.invalidCredentials { configuration = current; app.expire(account) }
                catch { saveError = error.localizedDescription; configuration = current }
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
