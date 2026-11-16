// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import SwiftUI
import PictureshowCore

@MainActor final class AppPreferences: ObservableObject {
    @Published private(set) var value: PlaybackPreferences
    @Published var error: String?
    private static var url: URL { FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("playback-preferences-v1.json") }
    init() {
        if let data = (try? Data(contentsOf: Self.url)) ?? UserDefaults.standard.data(forKey: "playback-preferences-v1"), let decoded = try? JSONDecoder().decode(PlaybackPreferences.self, from: data) { value = decoded }
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
    @State private var windowSize: CGSize = .zero
    @FocusState private var focused: String?
    @State private var opener = "bitrate"
    private let languages = ["", "eng", "spa", "fra", "deu", "jpn", "zho", "kor", "por", "ita", "rus", "ara", "hin"]
    private let languageNames = ["": "No preference", "eng": "English", "spa": "Spanish", "fra": "French", "deu": "German", "jpn": "Japanese", "zho": "Chinese", "kor": "Korean", "por": "Portuguese", "ita": "Italian", "rus": "Russian", "ara": "Arabic", "hin": "Hindi"]
    var body: some View {
        #if os(tvOS)
        // This runtime draws a native principal title over scrolled Form rows
        // even with a visible toolbar background. Reserve a separate header
        // viewport so the form can never scroll behind its title.
        VStack(spacing: 0) {
            Text("Settings").font(.title2).foregroundStyle(.white)
                .accessibilityAddTraits(.isHeader)
                .frame(maxWidth: .infinity).padding(.vertical, 24)
                .background(Theme.background)
            settingsForm
        }.navigationTitle("Settings").toolbar(.hidden, for: .navigationBar)
            .accessibilityIdentifier("settingsTitle").task { await load() }
        #else
        settingsForm.pictureshowNavigationTitle("Settings")
            .accessibilityIdentifier("settingsTitle").task { await load() }
        #endif
    }
    private var settingsForm: some View {
        Form {
            Section {
                settingPicker("Max streaming bitrate", selection: preference(\.bitrateMbps), choices: PlaybackPreferences.bitrates,
                              identifier: "maxBitrate", focusKey: "bitrate") { $0.map { "\($0) Mbps" } ?? "Auto (200 Mbps)" }
                Toggle(isOn: preference(\.showConversionNotice)) {
                    conversionNoticeLabel
                    #if os(iOS)
                    .onTapGesture { preferences.update { $0.showConversionNotice.toggle() } }
                    #endif
                }.focused($focused, equals: "conversionNotice")
                    .accessibilityLabel("Conversion notice").accessibilityIdentifier("conversionNoticeToggle")
            } header: { Text("Playback").foregroundStyle(Theme.muted).accessibilityIdentifier("playbackSectionHeader") }
              footer: { Text(audioOutputFooter).foregroundStyle(Theme.muted).accessibilityIdentifier("audioOutputExplanation") }
            Section {
                settingPicker("Subtitle size", selection: preference(\.subtitleSize), choices: SubtitleSize.allCases,
                              identifier: "subtitleSize") { $0.rawValue }
                settingPicker("Subtitle background", selection: preference(\.subtitleBackground), choices: SubtitleBackground.allCases,
                              identifier: "subtitleBackground") { $0.rawValue }
            } header: { Text("Subtitles").foregroundStyle(Theme.muted) }
              footer: { Text("Subtitles follow your Apple accessibility settings. Text subtitles use these choices when their format supports them. Subtitles already included in the picture cannot change appearance or timing.").font(.caption).foregroundStyle(Theme.muted) }
            Section {
                ForEach(SegmentType.allCases.filter { $0 != .unknown }, id: \.rawValue) { type in
                    segmentPicker(type)
                }
            } header: { Text("Skip segments").foregroundStyle(Theme.muted) }
            Section {
                if let configuration {
                    serverPicker("Audio language", key: "AudioLanguagePreference", value: configuration.audioLanguage, identifier: "audioLanguage")
                    serverPicker("Subtitle language", key: "SubtitleLanguagePreference", value: configuration.subtitleLanguage, identifier: "subtitleLanguage")
                    settingPicker("Subtitle mode", selection: Binding(get: { self.configuration?.subtitleMode ?? "Default" }, set: { save("SubtitleMode", $0) }),
                                  choices: ["Default", "Always", "OnlyForced", "None", "Smart"], identifier: "subtitleMode", disabled: serverBusy) { $0 }
                    Text("Jellyfin uses these preferences to choose the starting audio and subtitle tracks.").font(.caption).foregroundStyle(Theme.muted)
                } else { Text(serverBusy ? "Loading server preferences…" : "Server preferences unavailable").foregroundStyle(Theme.muted) }
                if serverBusy { ProgressView("Saving or loading…") }
                if let error = serverError { Text(error).foregroundStyle(.orange); Button("Retry server preferences") { Task { await load() } }.disabled(serverBusy) }
            } header: { Text("Jellyfin preferences").foregroundStyle(Theme.muted) }
            if let error = preferences.error { Section { Text(error).foregroundStyle(.orange) } }
            Section {
                NavigationLink("Accounts", value: AppRoute.accounts).accessibilityIdentifier("openAccounts").focused($focused, equals: "accounts")
                NavigationLink("Support Pictureshow", value: AppRoute.information("supporter")).accessibilityIdentifier("openSupporter").focused($focused, equals: "supporter")
                NavigationLink("About Pictureshow", value: AppRoute.information("about")).accessibilityIdentifier("openAbout").focused($focused, equals: "about")
                NavigationLink("Troubleshooting · This \(deviceNoun)", value: AppRoute.information("diagnostics")).accessibilityIdentifier("openDiagnostics").focused($focused, equals: "diagnostics")
            }
        }
        #if os(iOS)
        .scrollContentBackground(.hidden)
        #endif
        .background(Theme.background.ignoresSafeArea())
        .minimumTouchListRows()
        .background { GeometryReader { geometry in Color.clear.onAppear { windowSize = geometry.size }.onChange(of: geometry.size) { _, size in windowSize = size } } }
        .onAppear { focused = opener }
        .onChange(of: focused) { _, key in if let key { opener = key } }
        #if os(tvOS)
        .defaultFocus($focused, opener)
        #endif
    }
    private func segmentPicker(_ type: SegmentType) -> some View {
        let selection = Binding<SegmentMode>(
            get: { preferences.value.segmentModes[type] ?? .ask },
            set: { choice in preferences.update { $0.segmentModes[type] = choice } })
        return settingPicker(type.rawValue, selection: selection, choices: SegmentMode.allCases,
                             identifier: "segment-" + type.rawValue, label: segmentChoiceLabel)
    }
    private func segmentChoiceLabel(_ mode: SegmentMode) -> String { mode == .automatic ? "Skip automatically" : mode.rawValue }
    @ViewBuilder private func settingPicker<Value: Hashable>(_ title: String, selection: Binding<Value>, choices: [Value],
                                                            identifier: String, focusKey: String? = nil, disabled: Bool = false,
                                                            label: @escaping (Value) -> String) -> some View {
        #if os(tvOS)
        LabeledContent {
            // Native Picker's menu platter can resolve to white inside a TV tab
            // container. Own its visible value ink; retain the system menu,
            // selection checkmark, focus lift and the same preference binding.
            Menu {
                Picker(title, selection: selection) {
                    ForEach(choices, id: \.self) { choice in Text(label(choice)).tag(choice) }
                }
            } label: {
                TelevisionActionInk { Text(label(selection.wrappedValue)) }
            }
            .menuStyle(.button).buttonStyle(.bordered).tint(Optional<Color>.none)
            .disabled(disabled).focused($focused, equals: focusKey ?? identifier)
            .accessibilityLabel(title).accessibilityValue(label(selection.wrappedValue))
            .accessibilityIdentifier(identifier)
        } label: { Text(title).foregroundStyle(.white).accessibilityHidden(true) }
        #else
        Picker(title, selection: selection) {
            ForEach(choices, id: \.self) { choice in Text(label(choice)).tag(choice) }
        }.disabled(disabled).accessibilityIdentifier(identifier).focused($focused, equals: focusKey ?? identifier)
        #endif
    }
    private var conversionNoticeLabel: some View {
        VStack(alignment: .leading, spacing: 6) {
            #if os(tvOS)
            Text("Conversion notice").foregroundStyle(focused == "conversionNotice" ? Theme.background : Color.white)
            Text("Shows a short note when the server has to convert a video for this \(deviceNoun).")
                .font(.caption).foregroundStyle(focused == "conversionNotice" ? Theme.background.opacity(0.8) : Theme.muted)
                .fixedSize(horizontal: false, vertical: true)
            #else
            Text("Conversion notice")
            Text("Shows a short note when the server has to convert a video for this \(deviceNoun).")
                .font(.caption).foregroundStyle(Theme.muted).fixedSize(horizontal: false, vertical: true)
            #endif
        }.frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle())
    }
    private var audioOutputFooter: String {
        #if os(tvOS)
        "Apple TV chooses the audio output. Pictureshow doesn't send DTS or Dolby TrueHD directly to receivers."
        #else
        "\(UIDevice.current.userInterfaceIdiom == .pad ? "iPadOS" : "iOS") chooses the audio output. Pictureshow doesn't send DTS or Dolby TrueHD directly to receivers."
        #endif
    }
    private var deviceNoun: String {
        #if os(tvOS)
        "TV"
        #else
        min(windowSize.width, windowSize.height) >= 600 ? "tablet" : "phone"
        #endif
    }
    private func preference<T>(_ key: WritableKeyPath<PlaybackPreferences,T>) -> Binding<T> { Binding(get: { preferences.value[keyPath: key] }, set: { choice in preferences.update { $0[keyPath: key] = choice } }) }
    private func serverPicker(_ title: String, key: String, value: String, identifier: String) -> some View {
        settingPicker(title, selection: Binding(get: { key == "AudioLanguagePreference" ? configuration?.audioLanguage ?? "" : configuration?.subtitleLanguage ?? "" }, set: { save(key, $0) }),
                      choices: languages.contains(value) ? languages : languages + [value], identifier: identifier, disabled: serverBusy) { languageNames[$0] ?? $0 }
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
