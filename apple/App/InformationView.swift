// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import SwiftUI
import PictureshowCore
import AVFoundation
import VideoToolbox

struct InformationView: View {
    let kind: String
    var body: some View {
        switch kind {
        case "diagnostics": DiagnosticsView()
        case "licenses": LicenseListView()
        case "license-mpl": LicenseTextView()
        case "supporter": SupporterView()
        default: AboutView()
        }
    }
}
private struct AboutView: View {
    private var version: String { Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "Unreported" }
    private var build: String { Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "Unreported" }
    var body: some View {
        ReadingPage(title: "About Pictureshow") {
            Text("Pictureshow").font(.largeTitle.bold()).accessibilityAddTraits(.isHeader).accessibilityIdentifier("aboutTitle")
            Text("Your Jellyfin, beautifully at home.")
            Text("Version \(version) (\(build))")
            Text("Free and open source · MPL-2.0")
            Text("Copyright © Legitimate LLC")
            Text("Source: https://github.com/pictureshow/pictureshow").selectableReading()
            Text("Privacy: https://pictureshow.app/privacy/").selectableReading()
            NavigationLink("Open-source licenses", value: AppRoute.information("licenses")).buttonStyle(.borderedProminent).accessibilityIdentifier("openLicenses")
        }
    }
}
private struct LicenseListView: View {
    var body: some View { ReadingPage(title: "Open-source licenses") { Text("1 bundled license; 0 third-party package dependencies.").foregroundStyle(Theme.muted); NavigationLink(value: AppRoute.information("license-mpl")) { VStack(alignment: .leading) { Text("Pictureshow").font(.headline); Text("Application, PictureshowCore and PictureshowTransport · MPL-2.0") } }.buttonStyle(.bordered).accessibilityIdentifier("license-MPL-2.0"); Text("Apple SwiftUI, Foundation, AVKit, AVFoundation, VideoToolbox and system frameworks are provided by the operating system.").foregroundStyle(Theme.muted) } }
}
private struct LicenseTextView: View {
    private var text: String {
        guard let url = Bundle.main.url(forResource: "MPL-2.0", withExtension: "txt") else { return "The bundled license could not be loaded. Reinstall this build; the complete license is also in the Pictureshow source repository." }
        return (try? String(contentsOf: url, encoding: .utf8)) ?? "The bundled license could not be read."
    }
    var body: some View { ReadingPage(title: "Mozilla Public License 2.0") { Text("Mozilla Public License 2.0").font(.title).accessibilityAddTraits(.isHeader).accessibilityIdentifier("licenseTitle"); ReadingChunks(text: text) } }
}
private struct SupporterView: View {
    var body: some View {
        ReadingPage(title: "Support Pictureshow") {
            Text("Free and open source").font(.largeTitle.bold()).accessibilityAddTraits(.isHeader)
            ReadingChunks(text: "Pictureshow is free to use. Monthly supporters help sustain development, and their feature requests receive priority consideration. Support does not guarantee a feature, a delivery date, or a particular outcome.")
            Text("Support purchases are unavailable in this Apple build.").font(.title2).accessibilityIdentifier("billingUnavailable")
            Text("Apple in-app purchases are not configured. There is no subscription offer, price or active entitlement to display. All catalog and playback features remain available.").foregroundStyle(Theme.muted)
        }
    }
}
private struct DiagnosticsView: View {
    @EnvironmentObject private var model: AppModel
    @State private var report: [String] = []
    @State private var profile = ""
    @State private var showProfile = false
    @FocusState private var refreshFocused: Bool
    var body: some View {
        ReadingPage(title: "This device") {
            Text("This device").font(.largeTitle.bold()).accessibilityAddTraits(.isHeader)
            Button("Refresh", action: refresh).buttonStyle(.borderedProminent).focused($refreshFocused).accessibilityIdentifier("refreshDiagnostics")
            ForEach(Array(report.enumerated()), id: \.offset) { _, block in ReadingChunks(text: block) }
            Button(showProfile ? "Hide device profile JSON" : "View device profile JSON") { showProfile.toggle() }.buttonStyle(.bordered).accessibilityIdentifier("toggleProfile")
            if showProfile { ReadingChunks(text: profile) }
        }.onAppear { refresh(); refreshFocused = true }
    }
    private func refresh() {
        let audio = AVAudioSession.sharedInstance()
        let hevc = VTIsHardwareDecodeSupported(kCMVideoCodecType_HEVC)
        let h264 = VTIsHardwareDecodeSupported(kCMVideoCodecType_H264)
        let screen = UIScreen.main
        report = ["\(UIDevice.current.model) · \(UIDevice.current.systemName) \(UIDevice.current.systemVersion)", "Hardware video decoding: H.264 \(h264 ? "reported" : "unreported"); HEVC \(hevc ? "reported" : "unreported"). Decoder profile and level limits are unreported.", "Display: \(Int(screen.bounds.width)) × \(Int(screen.bounds.height)) points; scale \(Int(screen.scale)); maximum refresh \(screen.maximumFramesPerSecond) Hz. HDR and Dolby Vision capability are not inferred from the simulator or device name.", "Audio outputs: \(audio.currentRoute.outputs.map { $0.portType.rawValue }.joined(separator: ", ")). Current channels: \(audio.outputNumberOfChannels); maximum available: \(audio.maximumOutputNumberOfChannels). Encoded passthrough acceptance is unreported.", "AVKit handles transport controls, captions and external playback. Codec startup, HDMI audio, AirPlay and HDR require verification on the intended hardware."]
        if let data = try? JSONSerialization.data(withJSONObject: AppleProfile.json(supportsHEVC: hevc), options: [.prettyPrinted, .sortedKeys]), let text = String(data: data, encoding: .utf8) { profile = text } else { profile = "Device profile unavailable." }
    }
}

struct ReadingPage<Content: View>: View {
    let title: String
    @ViewBuilder var content: () -> Content
    var body: some View { ScrollView { VStack(alignment: .leading, spacing: 24, content: content).frame(maxWidth: 900, alignment: .leading).padding(Theme.gutter).frame(maxWidth: .infinity) }.navigationTitle(title).background(Theme.background.ignoresSafeArea()) }
}
struct ReadingChunks: View {
    let text: String
    private var blocks: [String] {
        var blocks: [String] = []; var current = ""
        for word in text.split(whereSeparator: \.isWhitespace) { if current.count + word.count > 700 { blocks.append(current); current = "" }; current += (current.isEmpty ? "" : " ") + word }
        if !current.isEmpty { blocks.append(current) }; return blocks
    }
    var body: some View { LazyVStack(alignment: .leading, spacing: 18) { ForEach(Array(blocks.enumerated()), id: \.offset) { _, block in Text(block).fixedSize(horizontal: false, vertical: true).selectableReading().readingFocusable() } } }
}
extension View {
    @ViewBuilder func selectableReading() -> some View {
        #if os(iOS)
        textSelection(.enabled)
        #else
        self
        #endif
    }
    @ViewBuilder func readingFocusable() -> some View {
        #if os(tvOS)
        focusable()
        #else
        self
        #endif
    }
}
