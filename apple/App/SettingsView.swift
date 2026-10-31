// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import SwiftUI
import PictureshowCore

@MainActor final class AppPreferences: ObservableObject {
    @Published private(set) var value: PlaybackPreferences
    @Published var error: String?
    private static var url: URL { FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("playback-preferences-v1.json") }
    init() {
        if let data = try? Data(contentsOf: Self.url) ?? UserDefaults.standard.data(forKey: "playback-preferences-v1"), let decoded = try? JSONDecoder().decode(PlaybackPreferences.self, from: data) { value = decoded }
        else { value = .init() }
    }
    func update(_ change: (inout PlaybackPreferences) -> Void) {
        var next = value; change(&next)
        do {
            let data = try JSONEncoder().encode(next)
            try FileManager.default.createDirectory(at: Self.url.deletingLastPathComponent(), withIntermediateDirectories: true)
            try data.write(to: Self.url, options: [.atomic, .completeFileProtectionUnlessOpen])
            UserDefaults.standard.set(data, forKey: "playback-preferences-v1")
            value = next; error = nil
        } catch { self.error = "This preference could not be saved. The last saved value is still selected." }
    }
}

struct SettingsView: View {
    @EnvironmentObject private var model: AppModel
    var body: some View { SettingsForm(preferences: model.preferences) }
}

private struct SettingsForm: View {
    @EnvironmentObject private var model: AppModel
    @ObservedObject var preferences: AppPreferences
    @State private var configuration: UserConfiguration?
    @State private var serverError: String?
    @State private var serverBusy = false
    private let languages = ["", "eng", "spa", "fra", "deu", "jpn", "zho", "kor", "por", "ita", "rus", "ara", "hin"]
    private let languageNames = ["": "No preference", "eng": "English", "spa": "Spanish", "fra": "French", "deu": "German", "jpn": "Japanese", "zho": "Chinese", "kor": "Korean", "por": "Portuguese", "ita": "Italian", "rus": "Russian", "ara": "Arabic", "hin": "Hindi"]
    var body: some View {
        Form {
            Section("Playback") {
                Picker("Max streaming bitrate", selection: preference(\.bitrateMbps)) { ForEach(PlaybackPreferences.bitrates, id: \.self) { value in Text(value.map { "\($0) Mbps" } ?? "Auto (200 Mbps)").tag(value) } }.accessibilityIdentifier("maxBitrate")
                Toggle("Audio passthrough", isOn: preference(\.passthrough)).accessibilityIdentifier("passthrough")
                Text("Encoded passthrough formats are unreported by this app. AVKit manages the current output route; no DTS or TrueHD passthrough is claimed.").font(.caption).foregroundStyle(Theme.muted)
            }
            Section("Captions") {
                Picker("Subtitle size", selection: preference(\.subtitleSize)) { ForEach(SubtitleSize.allCases, id: \.rawValue) { Text($0.rawValue).tag($0) } }
                Picker("Subtitle background", selection: preference(\.subtitleBackground)) { ForEach(SubtitleBackground.allCases, id: \.rawValue) { Text($0.rawValue).tag($0) } }
                Text("Native captions also follow Apple accessibility settings. App size/background overrides apply where AVKit permits WebVTT styling. Burned-in subtitles cannot change appearance or timing.").font(.caption).foregroundStyle(Theme.muted)
            }
            Section("Skip segments") {
                ForEach(SegmentType.allCases.filter { $0 != .unknown }, id: \.rawValue) { type in
                    Picker(type.rawValue, selection: Binding(get: { preferences.value.segmentModes[type] ?? .ask }, set: { choice in preferences.update { $0.segmentModes[type] = choice } })) { ForEach(SegmentMode.allCases, id: \.rawValue) { mode in Text(mode == .automatic ? "Skip automatically" : mode.rawValue).tag(mode) } }
                }
            }
            Section("Jellyfin preferences") {
                if let configuration {
                    serverPicker("Audio language", key: "AudioLanguagePreference", value: configuration.audioLanguage).accessibilityIdentifier("audioLanguage")
                    serverPicker("Subtitle language", key: "SubtitleLanguagePreference", value: configuration.subtitleLanguage).accessibilityIdentifier("subtitleLanguage")
                    Picker("Subtitle mode", selection: Binding(get: { self.configuration?.subtitleMode ?? "Default" }, set: { save("SubtitleMode", $0) })) { ForEach(["Default", "Always", "OnlyForced", "None", "Smart"], id: \.self) { Text($0).tag($0) } }.disabled(serverBusy).accessibilityIdentifier("subtitleMode")
                    Text("Jellyfin chooses default tracks using these preferences. Unknown server configuration fields are preserved.").font(.caption).foregroundStyle(Theme.muted)
                } else { Text(serverBusy ? "Loading server preferences…" : "Server preferences unavailable").foregroundStyle(Theme.muted) }
                if serverBusy { ProgressView("Saving or loading…") }
                if let error = serverError { Text(error).foregroundStyle(.orange); Button("Retry server preferences") { Task { await load() } }.disabled(serverBusy) }
            }
            if let error = preferences.error { Section { Text(error).foregroundStyle(.orange) } }
            Section {
                NavigationLink("Accounts", value: AppRoute.accounts).accessibilityIdentifier("openAccounts")
                NavigationLink("Support Pictureshow", value: AppRoute.information("supporter")).accessibilityIdentifier("openSupporter")
                NavigationLink("About Pictureshow", value: AppRoute.information("about")).accessibilityIdentifier("openAbout")
                NavigationLink("Troubleshooting", value: AppRoute.information("diagnostics")).accessibilityIdentifier("openDiagnostics")
            }
        }.navigationTitle("Settings").accessibilityIdentifier("settingsTitle").task { await load() }
    }
    private func preference<T>(_ key: WritableKeyPath<PlaybackPreferences,T>) -> Binding<T> { Binding(get: { preferences.value[keyPath: key] }, set: { choice in preferences.update { $0[keyPath: key] = choice } }) }
    private func serverPicker(_ title: String, key: String, value: String) -> some View {
        Picker(title, selection: Binding(get: { key == "AudioLanguagePreference" ? configuration?.audioLanguage ?? "" : configuration?.subtitleLanguage ?? "" }, set: { save(key, $0) })) {
            ForEach(languages, id: \.self) { language in Text(languageNames[language] ?? language).tag(language) }
            if !languages.contains(value) { Text(value).tag(value) }
        }.disabled(serverBusy)
    }
    private func load() async {
        guard let account = model.account, !serverBusy else { return }; serverBusy = true; serverError = nil
        defer { serverBusy = false }
        do { let result = try await model.client.configuration(account: account); guard model.account?.localKey == account.localKey else { return }; configuration = result }
        catch { model.handle(error, account: account); serverError = error.localizedDescription }
    }
    private func save(_ key: String, _ value: String) {
        guard !serverBusy, let configuration, let account = model.account else { return }; serverBusy = true; serverError = nil
        Task {
            defer { serverBusy = false }
            do { let changed = try configuration.withPreference(key, value: value); try await model.client.saveConfiguration(account: account, configuration: changed); guard model.account?.localKey == account.localKey else { return }; self.configuration = changed }
            catch { model.handle(error, account: account); serverError = error.localizedDescription }
        }
    }
}
