// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import AVFoundation
import SwiftUI
import VideoToolbox
import PictureshowCore

/// Troubleshooting: what this device tells the server it can play, read from the same sources the
/// player uses, so a viewer (or someone helping them) can see why a file was converted.
struct DeviceInfoView: View {
    @EnvironmentObject private var app: AppModel
    @State private var report: DeviceReport?
    @State private var showProfile = false

    var body: some View {
        Form {
            if let report {
                Section("Device") {
                    LabeledContent("Model", value: report.model)
                    LabeledContent("System", value: report.system)
                    LabeledContent("Pictureshow", value: "\(AppInfo.version) (\(AppInfo.build))")
                }
                Section {
                    LabeledContent("Screen", value: report.screen)
                    LabeledContent("HDR video", value: report.hdr ? "Supported" : "Not supported")
                } header: { Text("Display") }
                Section {
                    LabeledContent("Plays directly", value: "MP4, M4V and MOV files")
                    LabeledContent("H.264", value: "Up to 1080p, 8-bit")
                    LabeledContent("HEVC (H.265)", value: report.hevcDecode ? "Hardware decoding, up to 10-bit" : "Not supported, the server converts it")
                    LabeledContent("HEVC conversions", value: report.hevcTranscode)
                    LabeledContent("Audio", value: "AAC, MP3, ALAC, AC-3 and E-AC-3, up to 5.1")
                    LabeledContent("Streaming quality", value: app.preferences.playback.bitrateMbps.map { "Up to \($0) Mbps" } ?? "Best available")
                } header: {
                    Text("Video and audio")
                } footer: {
                    Text("Anything else (MKV files, DTS or TrueHD sound, HDR on this device) is converted by the server, and Pictureshow says so when it happens.")
                }
                Section {
                    Button(showProfile ? "Hide device profile" : "View device profile") { showProfile.toggle() }
                    if showProfile {
                        // One Text per line keeps a long report scrollable without one giant text layer.
                        ForEach(Array(report.profileLines.enumerated()), id: \.offset) { _, line in
                            Text(line).font(.system(.caption, design: .monospaced)).textSelection(.enabled)
                        }
                    }
                } footer: {
                    Text("This is what Pictureshow sends the server with every playback request.")
                }
            } else {
                ProgressView()
            }
            Section { Button("Refresh") { Task { await load() } } }
        }
        .navigationTitle(DeviceIdentity.thisDevice)
        #if os(iOS)
        .scrollContentBackground(.hidden)
        .pageBackground()
        #endif
        .task { await load() }
    }

    private func load() async {
        let hevcCapabilities = await AppleHEVCQualification.shared.capabilities()
        report = DeviceReport.current(preferences: app.preferences.playback, hevcCapabilities: hevcCapabilities)
    }
}

struct DeviceReport {
    var model: String
    var system: String
    var screen: String
    var hdr: Bool
    var hevcDecode: Bool
    var hevcTranscode: String
    var profileLines: [String]

    @MainActor static func current(preferences: PlaybackPreferences, hevcCapabilities: DeviceCapabilities?) -> DeviceReport {
        let device = UIDevice.current
        let screen = UIScreen.main
        let size = screen.nativeBounds.size
        let (w, h) = (Int(max(size.width, size.height)), Int(min(size.width, size.height)))
        let hevc = VTIsHardwareDecodeSupported(kCMVideoCodecType_HEVC)
        let profile = AppleProfile.json(supportsHEVC: hevc, maxStreamingBitrate: preferences.maxStreamingBitrate, hevcTranscodeCapabilities: hevcCapabilities)
        let json = (try? JSONSerialization.data(withJSONObject: profile, options: [.prettyPrinted, .sortedKeys]))
            .flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
        return DeviceReport(
            model: device.model,
            system: "\(device.systemName) \(device.systemVersion)",
            screen: "\(w) × \(h), up to \(screen.maximumFramesPerSecond) Hz",
            hdr: AVPlayer.eligibleForHDRPlayback,
            hevcDecode: hevc,
            hevcTranscode: hevcCapabilities == nil ? "Asks for H.264" : "Asks for HEVC up to 1080p",
            profileLines: json.components(separatedBy: "\n"))
    }
}

struct AboutView: View {
    var body: some View {
        Form {
            Section {
                VStack(alignment: .leading, spacing: 8) {
                    Wordmark()
                    Text("A free, open-source app for watching your Jellyfin library.").foregroundStyle(Theme.muted)
                }
                .padding(.vertical, 6)
                LabeledContent("Version", value: "\(AppInfo.version) (\(AppInfo.build))")
                LabeledContent("License", value: "Mozilla Public License 2.0")
                LabeledContent("Made by", value: "Legitimate LLC")
            }
            Section {
                NavigationLink("Open-source license") { LicenseView() }
                #if os(iOS)
                Link("Source code", destination: URL(string: "https://github.com/pictureshow/pictureshow")!)
                Link("Privacy policy", destination: URL(string: "https://pictureshow.app/privacy/")!)
                #else
                // Apple TV has no browser, so show the addresses to open on another device.
                LabeledContent("Source code", value: "github.com/pictureshow/pictureshow")
                LabeledContent("Privacy policy", value: "pictureshow.app/privacy")
                #endif
            }
        }
        .navigationTitle("About Pictureshow")
        #if os(iOS)
        .scrollContentBackground(.hidden)
        .pageBackground()
        #endif
    }
}
